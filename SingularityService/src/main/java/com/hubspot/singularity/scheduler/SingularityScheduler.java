package com.hubspot.singularity.scheduler;

import java.text.ParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.TaskStatus.Reason;
import org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.DeployState;
import com.hubspot.singularity.ExtendedTaskState;
import com.hubspot.singularity.MachineState;
import com.hubspot.singularity.RequestState;
import com.hubspot.singularity.RequestType;
import com.hubspot.singularity.ScheduleType;
import com.hubspot.singularity.SingularityCreateResult;
import com.hubspot.singularity.SingularityDeployMarker;
import com.hubspot.singularity.SingularityDeployStatistics;
import com.hubspot.singularity.SingularityDeployStatisticsBuilder;
import com.hubspot.singularity.SingularityDeployProgress;
import com.hubspot.singularity.SingularityMachineAbstraction;
import com.hubspot.singularity.SingularityPendingDeploy;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityPendingRequest.PendingType;
import com.hubspot.singularity.SingularityPendingTask;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityRack;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.SingularityRequestWithState;
import com.hubspot.singularity.SingularitySlave;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskCleanup;
import com.hubspot.singularity.SingularityTaskHistoryUpdate;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityTaskRequest;
import com.hubspot.singularity.TaskCleanupType;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.AbstractMachineManager;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.RackManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.SlaveManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.TaskRequestManager;
import com.hubspot.singularity.helpers.RFC5545Schedule;
import com.hubspot.singularity.smtp.SingularityMailer;

@Singleton
public class SingularityScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityScheduler.class);

  private final SingularityConfiguration configuration;
  private final SingularityCooldown cooldown;

  private final TaskManager taskManager;
  private final RequestManager requestManager;
  private final TaskRequestManager taskRequestManager;
  private final DeployManager deployManager;

  private final SlaveManager slaveManager;
  private final RackManager rackManager;

  private final SingularityMailer mailer;

  @Inject
  public SingularityScheduler(TaskRequestManager taskRequestManager, SingularityConfiguration configuration, SingularityCooldown cooldown, DeployManager deployManager,
    TaskManager taskManager, RequestManager requestManager, SlaveManager slaveManager, RackManager rackManager, SingularityMailer mailer) {
    this.taskRequestManager = taskRequestManager;
    this.configuration = configuration;
    this.deployManager = deployManager;
    this.taskManager = taskManager;
    this.requestManager = requestManager;
    this.slaveManager = slaveManager;
    this.rackManager = rackManager;
    this.mailer = mailer;
    this.cooldown = cooldown;
  }

  private void cleanupTaskDueToDecomission(final Map<String, Optional<String>> requestIdsToUserToReschedule, final Set<SingularityTaskId> matchingTaskIds, SingularityTask task,
    SingularityMachineAbstraction<?> decommissioningObject) {
    requestIdsToUserToReschedule.put(task.getTaskRequest().getRequest().getId(), decommissioningObject.getCurrentState().getUser());

    matchingTaskIds.add(task.getTaskId());

    LOG.trace("Scheduling a cleanup task for {} due to decomissioning {}", task.getTaskId(), decommissioningObject);

    taskManager.createTaskCleanup(new SingularityTaskCleanup(decommissioningObject.getCurrentState().getUser(), TaskCleanupType.DECOMISSIONING, System.currentTimeMillis(),
      task.getTaskId(), Optional.of(String.format("%s %s is decomissioning", decommissioningObject.getTypeName(), decommissioningObject.getName())), Optional.<String>absent()));
  }

  private <T extends SingularityMachineAbstraction<T>> Map<T, MachineState> getDefaultMap(List<T> objects) {
    Map<T, MachineState> map = Maps.newHashMapWithExpectedSize(objects.size());
    for (T object : objects) {
      map.put(object, MachineState.DECOMMISSIONING);
    }
    return map;
  }

  @Timed
  public void checkForDecomissions(SingularitySchedulerStateCache stateCache) {
    final long start = System.currentTimeMillis();

    final Map<String, Optional<String>> requestIdsToUserToReschedule = Maps.newHashMap();
    final Set<SingularityTaskId> matchingTaskIds = Sets.newHashSet();

    final Collection<SingularityTaskId> activeTaskIds = stateCache.getActiveTaskIds();

    final Map<SingularitySlave, MachineState> slaves = getDefaultMap(slaveManager.getObjectsFiltered(MachineState.STARTING_DECOMMISSION));

    for (SingularitySlave slave : slaves.keySet()) {
      boolean foundTask = false;

      for (SingularityTask activeTask : taskManager.getTasksOnSlave(activeTaskIds, slave)) {
        cleanupTaskDueToDecomission(requestIdsToUserToReschedule, matchingTaskIds, activeTask, slave);
        foundTask = true;
      }

      if (!foundTask) {
        slaves.put(slave, MachineState.DECOMMISSIONED);
      }
    }

    final Map<SingularityRack, MachineState> racks = getDefaultMap(rackManager.getObjectsFiltered(MachineState.STARTING_DECOMMISSION));

    for (SingularityRack rack : racks.keySet()) {
      final String sanitizedRackId = JavaUtils.getReplaceHyphensWithUnderscores(rack.getId());

      boolean foundTask = false;

      for (SingularityTaskId activeTaskId : activeTaskIds) {
        if (sanitizedRackId.equals(activeTaskId.getSanitizedRackId())) {
          foundTask = true;
        }

        if (matchingTaskIds.contains(activeTaskId)) {
          continue;
        }

        if (sanitizedRackId.equals(activeTaskId.getSanitizedRackId())) {
          Optional<SingularityTask> maybeTask = taskManager.getTask(activeTaskId);
          cleanupTaskDueToDecomission(requestIdsToUserToReschedule, matchingTaskIds, maybeTask.get(), rack);
        }
      }

      if (!foundTask) {
        racks.put(rack, MachineState.DECOMMISSIONED);
      }
    }

    for (Entry<String, Optional<String>> requestIdAndUser : requestIdsToUserToReschedule.entrySet()) {
      final String requestId = requestIdAndUser.getKey();

      LOG.trace("Rescheduling request {} due to decomissions", requestId);

      Optional<String> maybeDeployId = deployManager.getInUseDeployId(requestId);

      if (maybeDeployId.isPresent()) {
        requestManager.addToPendingQueue(
          new SingularityPendingRequest(requestId, maybeDeployId.get(), start, requestIdAndUser.getValue(), PendingType.DECOMISSIONED_SLAVE_OR_RACK, Optional.<Boolean>absent(),
            Optional.<String>absent()));
      } else {
        LOG.warn("Not rescheduling a request ({}) because of no active deploy", requestId);
      }
    }

    changeState(slaves, slaveManager);
    changeState(racks, rackManager);

    if (slaves.isEmpty() && racks.isEmpty() && requestIdsToUserToReschedule.isEmpty() && matchingTaskIds.isEmpty()) {
      LOG.trace("Decomission check found nothing");
    } else {
      LOG.info("Found {} decomissioning slaves, {} decomissioning racks, rescheduling {} requests and scheduling {} tasks for cleanup in {}", slaves.size(), racks.size(),
        requestIdsToUserToReschedule.size(), matchingTaskIds.size(), JavaUtils.duration(start));
    }
  }

  private <T extends SingularityMachineAbstraction<T>> void changeState(Map<T, MachineState> map, AbstractMachineManager<T> manager) {
    for (Entry<T, MachineState> entry : map.entrySet()) {
      manager.changeState(entry.getKey().getId(), entry.getValue(), entry.getKey().getCurrentState().getMessage(), entry.getKey().getCurrentState().getUser());
    }
  }

  @Timed
  public void drainPendingQueue(final SingularitySchedulerStateCache stateCache) {
    final long start = System.currentTimeMillis();

    final ImmutableList<SingularityPendingRequest> pendingRequests = ImmutableList.copyOf(requestManager.getPendingRequests());

    if (pendingRequests.isEmpty()) {
      LOG.trace("Pending queue was empty");
      return;
    }

    LOG.info("Pending queue had {} requests", pendingRequests.size());

    int totalNewScheduledTasks = 0;
    int heldForScheduledActiveTask = 0;
    int obsoleteRequests = 0;

    for (SingularityPendingRequest pendingRequest : pendingRequests) {
      Optional<SingularityRequestWithState> maybeRequest = requestManager.getRequest(pendingRequest.getRequestId());

      if (!isRequestActive(maybeRequest)) {
        LOG.debug("Pending request {} was obsolete (request {})", pendingRequest, SingularityRequestWithState.getRequestState(maybeRequest));
        obsoleteRequests++;
        requestManager.deletePendingRequest(pendingRequest);
        continue;
      }

      Optional<SingularityRequestDeployState> maybeRequestDeployState = deployManager.getRequestDeployState(pendingRequest.getRequestId());
      Optional<SingularityPendingDeploy> maybePendingDeploy = deployManager.getPendingDeploy(maybeRequest.get().getRequest().getId());

      final SingularityRequest updatedRequest;
      if (maybePendingDeploy.isPresent() && pendingRequest.getDeployId().equals(maybePendingDeploy.get().getDeployMarker().getDeployId())) {
        updatedRequest = maybePendingDeploy.get().getUpdatedRequest().or(maybeRequest.get().getRequest());
      } else {
        updatedRequest = maybeRequest.get().getRequest();
      }

      if (!shouldScheduleTasks(updatedRequest, pendingRequest, maybePendingDeploy, maybeRequestDeployState)) {
        LOG.debug("Pending request {} was obsolete (request {})", pendingRequest, SingularityRequestWithState.getRequestState(maybeRequest));
        obsoleteRequests++;
        requestManager.deletePendingRequest(pendingRequest);
        continue;
      }

      final List<SingularityTaskId> matchingTaskIds = getMatchingTaskIds(stateCache, updatedRequest, pendingRequest);

      final SingularityDeployStatistics deployStatistics = getDeployStatistics(pendingRequest.getRequestId(), pendingRequest.getDeployId());

      final RequestState requestState = checkCooldown(maybeRequest.get().getState(), updatedRequest, deployStatistics);

      int numScheduledTasks = scheduleTasks(stateCache, updatedRequest, requestState, deployStatistics, pendingRequest, matchingTaskIds, maybePendingDeploy);

      if (numScheduledTasks == 0 && !matchingTaskIds.isEmpty() && updatedRequest.isScheduled() && pendingRequest.getPendingType() == PendingType.NEW_DEPLOY) {
        LOG.trace("Holding pending request {} because it is scheduled and has an active task", pendingRequest);
        heldForScheduledActiveTask++;
        continue;
      }

      LOG.debug("Pending request {} resulted in {} new scheduled tasks", pendingRequest, numScheduledTasks);

      totalNewScheduledTasks += numScheduledTasks;

      requestManager.deletePendingRequest(pendingRequest);
    }

    LOG.info("Scheduled {} new tasks ({} obsolete requests, {} held) in {}", totalNewScheduledTasks, obsoleteRequests, heldForScheduledActiveTask, JavaUtils.duration(start));
  }

  private RequestState checkCooldown(RequestState requestState, SingularityRequest request, SingularityDeployStatistics deployStatistics) {
    if (requestState != RequestState.SYSTEM_COOLDOWN) {
      return requestState;
    }

    if (cooldown.hasCooldownExpired(request, deployStatistics, Optional.<Integer>absent(), Optional.<Long>absent())) {
      requestManager.exitCooldown(request, System.currentTimeMillis(), Optional.<String>absent(), Optional.<String>absent());
      return RequestState.ACTIVE;
    }

    return requestState;
  }

  private boolean shouldScheduleTasks(SingularityRequest request, SingularityPendingRequest pendingRequest, Optional<SingularityPendingDeploy> maybePendingDeploy,
    Optional<SingularityRequestDeployState> maybeRequestDeployState) {
    if (request.isDeployable() && pendingRequest.getPendingType() == PendingType.NEW_DEPLOY && !maybePendingDeploy.isPresent()) {
      return false;
    }
    if (request.getRequestType() == RequestType.RUN_ONCE && pendingRequest.getPendingType() == PendingType.NEW_DEPLOY) {
      return true;
    }

    return isDeployInUse(maybeRequestDeployState, pendingRequest.getDeployId(), false);
  }

  @Timed
  public List<SingularityTaskRequest> getDueTasks() {
    final List<SingularityPendingTask> tasks = taskManager.getPendingTasks();

    final long now = System.currentTimeMillis();

    final List<SingularityPendingTask> dueTasks = Lists.newArrayListWithCapacity(tasks.size());

    for (SingularityPendingTask task : tasks) {
      if (task.getPendingTaskId().getNextRunAt() <= now) {
        dueTasks.add(task);
      }
    }

    final List<SingularityTaskRequest> dueTaskRequests = taskRequestManager.getTaskRequests(dueTasks);

    return checkForStaleScheduledTasks(dueTasks, dueTaskRequests);
  }

  private List<SingularityTaskRequest> checkForStaleScheduledTasks(List<SingularityPendingTask> pendingTasks, List<SingularityTaskRequest> taskRequests) {
    final Set<String> foundPendingTaskId = Sets.newHashSetWithExpectedSize(taskRequests.size());
    final Set<String> requestIds = Sets.newHashSetWithExpectedSize(taskRequests.size());

    for (SingularityTaskRequest taskRequest : taskRequests) {
      foundPendingTaskId.add(taskRequest.getPendingTask().getPendingTaskId().getId());
      requestIds.add(taskRequest.getRequest().getId());
    }

    for (SingularityPendingTask pendingTask : pendingTasks) {
      if (!foundPendingTaskId.contains(pendingTask.getPendingTaskId().getId())) {
        LOG.info("Removing stale pending task {}", pendingTask.getPendingTaskId());
        taskManager.deletePendingTask(pendingTask.getPendingTaskId());
      }
    }

    // TODO this check isn't necessary if we keep track better during deploys
    final Map<String, SingularityRequestDeployState> deployStates = deployManager.getRequestDeployStatesByRequestIds(requestIds);
    final List<SingularityTaskRequest> taskRequestsWithValidDeploys = Lists.newArrayListWithCapacity(taskRequests.size());

    for (SingularityTaskRequest taskRequest : taskRequests) {
      SingularityRequestDeployState requestDeployState = deployStates.get(taskRequest.getRequest().getId());

      if (!matchesDeploy(requestDeployState, taskRequest) && !(taskRequest.getRequest().getRequestType() == RequestType.RUN_ONCE)) {
        LOG.info("Removing stale pending task {} because the deployId did not match active/pending deploys {}", taskRequest.getPendingTask().getPendingTaskId(), requestDeployState);
        taskManager.deletePendingTask(taskRequest.getPendingTask().getPendingTaskId());
      } else {
        taskRequestsWithValidDeploys.add(taskRequest);
      }
    }

    return taskRequestsWithValidDeploys;
  }

  private boolean matchesDeploy(SingularityRequestDeployState requestDeployState, SingularityTaskRequest taskRequest) {
    if (requestDeployState == null) {
      return false;
    }
    return matchesDeployMarker(requestDeployState.getActiveDeploy(), taskRequest.getDeploy().getId())
      || matchesDeployMarker(requestDeployState.getPendingDeploy(), taskRequest.getDeploy().getId());
  }

  private boolean matchesDeployMarker(Optional<SingularityDeployMarker> deployMarker, String deployId) {
    return deployMarker.isPresent() && deployMarker.get().getDeployId().equals(deployId);
  }

  private void deleteScheduledTasks(final Collection<SingularityPendingTask> scheduledTasks, SingularityPendingRequest pendingRequest) {
    for (SingularityPendingTask task : Iterables
      .filter(scheduledTasks, Predicates.and(SingularityPendingTask.matchingRequest(pendingRequest.getRequestId()), SingularityPendingTask.matchingDeploy(pendingRequest.getDeployId())))) {
      LOG.debug("Deleting pending task {} in order to reschedule {}", task.getPendingTaskId().getId(), pendingRequest);
      taskManager.deletePendingTask(task.getPendingTaskId());
    }
  }

  private List<SingularityTaskId> getMatchingTaskIds(SingularitySchedulerStateCache stateCache, SingularityRequest request, SingularityPendingRequest pendingRequest) {
    if (request.isLongRunning()) {
      Collection<SingularityTaskId> exclude = Sets.newHashSet();
      exclude.addAll(stateCache.getCleaningTasks());
      exclude.addAll(stateCache.getKilledTasks());
      return SingularityTaskId.matchingAndNotIn(stateCache.getActiveTaskIds(), request.getId(), pendingRequest.getDeployId(), exclude);
    } else {
      return Lists.newArrayList(Iterables.filter(stateCache.getActiveTaskIds(), SingularityTaskId.matchingRequest(request.getId())));
    }
  }

  private int scheduleTasks(SingularitySchedulerStateCache stateCache, SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics,
    SingularityPendingRequest pendingRequest, List<SingularityTaskId> matchingTaskIds, Optional<SingularityPendingDeploy> maybePendingDeploy) {
    if (request.getRequestType() != RequestType.ON_DEMAND) {
      deleteScheduledTasks(stateCache.getScheduledTasks(), pendingRequest);
    }

    final int numMissingInstances = getNumMissingInstances(matchingTaskIds, request, pendingRequest, maybePendingDeploy);

    LOG.debug("Missing {} instances of request {} (matching tasks: {}), pending request: {}, pending deploy: {}", numMissingInstances, request.getId(), matchingTaskIds, pendingRequest,
      maybePendingDeploy);

    if (numMissingInstances > 0) {
      final List<SingularityPendingTask> scheduledTasks =
        getScheduledTaskIds(numMissingInstances, matchingTaskIds, request, state, deployStatistics, pendingRequest.getDeployId(), pendingRequest, maybePendingDeploy);

      if (!scheduledTasks.isEmpty()) {
        LOG.trace("Scheduling tasks: {}", scheduledTasks);

        for (SingularityPendingTask scheduledTask : scheduledTasks) {
          taskManager.savePendingTask(scheduledTask);
        }
      } else {
        LOG.info("No new scheduled tasks found for {}, setting state to {}", request.getId(), RequestState.FINISHED);
        requestManager.finish(request, System.currentTimeMillis());
      }
    } else if (numMissingInstances < 0) {
      final long now = System.currentTimeMillis();

      if (maybePendingDeploy.isPresent() && maybePendingDeploy.get().getDeployProgress().isPresent()) {
        Collections.sort(matchingTaskIds, SingularityTaskId.INSTANCE_NO_COMPARATOR); // For deploy steps we replace lowest instances first, so clean those
      } else {
        Collections.sort(matchingTaskIds, Collections.reverseOrder(SingularityTaskId.INSTANCE_NO_COMPARATOR)); // clean the highest numbers
      }

      for (int i = 0; i < Math.abs(numMissingInstances); i++) {
        final SingularityTaskId toCleanup = matchingTaskIds.get(i);

        LOG.info("Cleaning up task {} due to new request {} - scaling down to {} instances", toCleanup.getId(), request.getId(), request.getInstancesSafe());

        taskManager.createTaskCleanup(new SingularityTaskCleanup(pendingRequest.getUser(), TaskCleanupType.SCALING_DOWN, now, toCleanup, Optional.<String>absent(), Optional.<String>absent()));
      }
    }

    return numMissingInstances;
  }

  private boolean isRequestActive(Optional<SingularityRequestWithState> maybeRequestWithState) {
    return SingularityRequestWithState.isActive(maybeRequestWithState);
  }

  private boolean isDeployInUse(Optional<SingularityRequestDeployState> requestDeployState, String deployId, boolean mustMatchActiveDeploy) {
    if (!requestDeployState.isPresent()) {
      return false;
    }

    if (matchesDeployMarker(requestDeployState.get().getActiveDeploy(), deployId)) {
      return true;
    }

    if (mustMatchActiveDeploy) {
      return false;
    }

    return matchesDeployMarker(requestDeployState.get().getPendingDeploy(), deployId);
  }

  private Optional<PendingType> handleCompletedTaskWithStatistics(Optional<SingularityTask> task, SingularityTaskId taskId, long timestamp, ExtendedTaskState state,
    SingularityDeployStatistics deployStatistics, SingularityCreateResult taskHistoryUpdateCreateResult, SingularitySchedulerStateCache stateCache, Protos.TaskStatus status) {
    final Optional<SingularityRequestWithState> maybeRequestWithState = requestManager.getRequest(taskId.getRequestId());
    final Optional<SingularityPendingDeploy> maybePendingDeploy = deployManager.getPendingDeploy(taskId.getRequestId());

    if (!isRequestActive(maybeRequestWithState)) {
      LOG.warn("Not scheduling a new task, {} is {}", taskId.getRequestId(), SingularityRequestWithState.getRequestState(maybeRequestWithState));
      return Optional.absent();
    }

    RequestState requestState = maybeRequestWithState.get().getState();
    final SingularityRequest request = maybePendingDeploy.isPresent() ? maybePendingDeploy.get().getUpdatedRequest().or(maybeRequestWithState.get().getRequest()) : maybeRequestWithState.get().getRequest();

    final Optional<SingularityRequestDeployState> requestDeployState = deployManager.getRequestDeployState(request.getId());

    if (!isDeployInUse(requestDeployState, taskId.getDeployId(), true)) {
      LOG.debug("Task {} completed, but it didn't match active deploy state {} - ignoring", taskId.getId(), requestDeployState);
      return Optional.absent();
    }

    if (taskHistoryUpdateCreateResult == SingularityCreateResult.CREATED && requestState != RequestState.SYSTEM_COOLDOWN) {
      mailer.queueTaskCompletedMail(task, taskId, request, state);
    } else if (requestState == RequestState.SYSTEM_COOLDOWN) {
      LOG.debug("Not sending a task completed email because task {} is in SYSTEM_COOLDOWN", taskId);
    } else {
      LOG.debug("Not sending a task completed email for task {} because Singularity already processed this update", taskId);
    }

    if (!status.hasReason() || !status.getReason().equals(Reason.REASON_INVALID_OFFERS)) {
      if (!state.isSuccess() && taskHistoryUpdateCreateResult == SingularityCreateResult.CREATED && cooldown.shouldEnterCooldown(request, taskId, requestState, deployStatistics, timestamp)) {
        LOG.info("Request {} is entering cooldown due to task {}", request.getId(), taskId);
        requestState = RequestState.SYSTEM_COOLDOWN;
        requestManager.cooldown(request, System.currentTimeMillis());
        mailer.sendRequestInCooldownMail(request);
      }
    } else {
      LOG.debug("Not triggering cooldown due to TASK_LOST from invalid offers for request {}", request.getId());
    }

    PendingType pendingType = PendingType.TASK_DONE;

    if (!state.isSuccess() && shouldRetryImmediately(request, deployStatistics)) {
      LOG.debug("Retrying {} because {}", request.getId(), state);
      pendingType = PendingType.RETRY;
    } else if (!request.isAlwaysRunning()) {
      return Optional.absent();
    }

    if (state.isSuccess() && requestState == RequestState.SYSTEM_COOLDOWN) {
      // TODO send not cooldown anymore email
      LOG.info("Request {} succeeded a task, removing from cooldown", request.getId());
      requestState = RequestState.ACTIVE;
      requestManager.exitCooldown(request, System.currentTimeMillis(), Optional.<String>absent(), Optional.<String>absent());
    }

    SingularityPendingRequest pendingRequest = new SingularityPendingRequest(request.getId(), requestDeployState.get().getActiveDeploy().get().getDeployId(),
      System.currentTimeMillis(), Optional.<String>absent(), pendingType, Optional.<Boolean>absent(), Optional.<String>absent());

    scheduleTasks(stateCache, request, requestState, deployStatistics, pendingRequest, getMatchingTaskIds(stateCache, request, pendingRequest), maybePendingDeploy);

    return Optional.of(pendingType);
  }

  private SingularityDeployStatistics getDeployStatistics(String requestId, String deployId) {
    final Optional<SingularityDeployStatistics> maybeDeployStatistics = deployManager.getDeployStatistics(requestId, deployId);

    if (maybeDeployStatistics.isPresent()) {
      return maybeDeployStatistics.get();
    }

    return new SingularityDeployStatisticsBuilder(requestId, deployId).build();
  }

  @Timed
  public void handleCompletedTask(Optional<SingularityTask> task, SingularityTaskId taskId, boolean wasActive, long timestamp, ExtendedTaskState state,
    SingularityCreateResult taskHistoryUpdateCreateResult, SingularitySchedulerStateCache stateCache, Protos.TaskStatus status) {
    final SingularityDeployStatistics deployStatistics = getDeployStatistics(taskId.getRequestId(), taskId.getDeployId());

    if (wasActive) {
      taskManager.deleteActiveTask(taskId.getId());
      stateCache.getActiveTaskIds().remove(taskId);
    }

    if (!task.isPresent() || task.get().getTaskRequest().getRequest().isLoadBalanced()) {
      taskManager.createLBCleanupTask(taskId);
    }

    final Optional<PendingType> scheduleResult = handleCompletedTaskWithStatistics(task, taskId, timestamp, state, deployStatistics, taskHistoryUpdateCreateResult, stateCache, status);

    if (taskHistoryUpdateCreateResult == SingularityCreateResult.EXISTED) {
      return;
    }

    updateDeployStatistics(deployStatistics, taskId, timestamp, state, scheduleResult);
  }

  private void updateDeployStatistics(SingularityDeployStatistics deployStatistics, SingularityTaskId taskId, long timestamp, ExtendedTaskState state, Optional<PendingType> scheduleResult) {
    SingularityDeployStatisticsBuilder bldr = deployStatistics.toBuilder();

    if (bldr.getAverageRuntimeMillis().isPresent()) {
      long newAvgRuntimeMillis = (bldr.getAverageRuntimeMillis().get() * bldr.getNumTasks() + (timestamp - taskId.getStartedAt())) / (bldr.getNumTasks() + 1);

      bldr.setAverageRuntimeMillis(Optional.of(newAvgRuntimeMillis));
    } else {
      bldr.setAverageRuntimeMillis(Optional.of(timestamp - taskId.getStartedAt()));
    }

    bldr.setNumTasks(bldr.getNumTasks() + 1);

    if (!bldr.getLastFinishAt().isPresent() || timestamp > bldr.getLastFinishAt().get()) {
      bldr.setLastFinishAt(Optional.of(timestamp));
      bldr.setLastTaskState(Optional.of(state));
    }

    final ListMultimap<Integer, Long> instanceSequentialFailureTimestamps = bldr.getInstanceSequentialFailureTimestamps();
    final List<Long> sequentialFailureTimestamps = instanceSequentialFailureTimestamps.get(taskId.getInstanceNo());

    if (!state.isSuccess()) {
      if (SingularityTaskHistoryUpdate.getUpdate(taskManager.getTaskHistoryUpdates(taskId), ExtendedTaskState.TASK_CLEANING).isPresent()) {
        LOG.debug("{} failed with {} after cleaning - ignoring it for cooldown", taskId, state);
      } else {

        if (sequentialFailureTimestamps.size() < configuration.getCooldownAfterFailures()) {
          sequentialFailureTimestamps.add(timestamp);
        } else if (timestamp > sequentialFailureTimestamps.get(0)) {
          sequentialFailureTimestamps.set(0, timestamp);
        }

        Collections.sort(sequentialFailureTimestamps);
      }
    } else {
      bldr.setNumSuccess(bldr.getNumSuccess() + 1);
      sequentialFailureTimestamps.clear();
    }

    if (scheduleResult.isPresent() && scheduleResult.get() == PendingType.RETRY) {
      bldr.setNumSequentialRetries(bldr.getNumSequentialRetries() + 1);
    } else {
      bldr.setNumSequentialRetries(0);
    }

    final SingularityDeployStatistics newStatistics = bldr.build();

    LOG.trace("Saving new deploy statistics {}", newStatistics);

    deployManager.saveDeployStatistics(newStatistics);
  }

  private boolean shouldRetryImmediately(SingularityRequest request, SingularityDeployStatistics deployStatistics) {
    if (!request.getNumRetriesOnFailure().isPresent()) {
      return false;
    }

    final int numRetriesInARow = deployStatistics.getNumSequentialRetries();

    if (numRetriesInARow >= request.getNumRetriesOnFailure().get()) {
      LOG.debug("Request {} had {} retries in a row, not retrying again (num retries on failure: {})", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure());
      return false;
    }

    LOG.debug("Request {} had {} retries in a row - retrying again (num retries on failure: {})", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure());

    return true;
  }

  private int getNumMissingInstances(List<SingularityTaskId> matchingTaskIds, SingularityRequest request, SingularityPendingRequest pendingRequest,
    Optional<SingularityPendingDeploy> maybePendingDeploy) {
    if (request.isOneOff()) {
      if (pendingRequest.getPendingType() == PendingType.ONEOFF) {
        return 1;
      } else {
        return 0;
      }
    } else if (request.getRequestType() == RequestType.RUN_ONCE && pendingRequest.getPendingType() == PendingType.NEW_DEPLOY) {
      return 1;
    }

    return numInstancesExpected(request, pendingRequest, maybePendingDeploy) - matchingTaskIds.size();
  }

  private int numInstancesExpected(SingularityRequest request, SingularityPendingRequest pendingRequest, Optional<SingularityPendingDeploy> maybePendingDeploy) {
    if (!maybePendingDeploy.isPresent() || (maybePendingDeploy.get().getCurrentDeployState() == DeployState.CANCELED) || !maybePendingDeploy.get().getDeployProgress().isPresent()) {
      return request.getInstancesSafe();
    }

    SingularityDeployProgress deployProgress = maybePendingDeploy.get().getDeployProgress().get();
    if (maybePendingDeploy.get().getDeployMarker().getDeployId().equals(pendingRequest.getDeployId())) {
      return deployProgress.getTargetActiveInstances();
    } else {
      if (deployProgress.isStepComplete()) {
        return Math.max(request.getInstancesSafe() - deployProgress.getTargetActiveInstances(), 0);
      } else {
        return request.getInstancesSafe() - (Math.max(deployProgress.getTargetActiveInstances() - deployProgress.getDeployInstanceCountPerStep(), 0));
      }
    }
  }

  private List<SingularityPendingTask> getScheduledTaskIds(int numMissingInstances, List<SingularityTaskId> matchingTaskIds, SingularityRequest request, RequestState state,
    SingularityDeployStatistics deployStatistics, String deployId, SingularityPendingRequest pendingRequest, Optional<SingularityPendingDeploy> maybePendingDeploy) {
    final Optional<Long> nextRunAt = getNextRunAt(request, state, deployStatistics, pendingRequest.getPendingType(), maybePendingDeploy);

    if (!nextRunAt.isPresent()) {
      return Collections.emptyList();
    }

    final Set<Integer> inuseInstanceNumbers = Sets.newHashSetWithExpectedSize(matchingTaskIds.size());

    for (SingularityTaskId matchingTaskId : matchingTaskIds) {
      inuseInstanceNumbers.add(matchingTaskId.getInstanceNo());
    }

    final List<SingularityPendingTask> newTasks = Lists.newArrayListWithCapacity(numMissingInstances);

    int nextInstanceNumber = 1;

    for (int i = 0; i < numMissingInstances; i++) {
      while (inuseInstanceNumbers.contains(nextInstanceNumber)) {
        nextInstanceNumber++;
      }

      newTasks
        .add(new SingularityPendingTask(new SingularityPendingTaskId(request.getId(), deployId, nextRunAt.get(), nextInstanceNumber, pendingRequest.getPendingType(), pendingRequest.getTimestamp()),
          pendingRequest.getCmdLineArgsList(), pendingRequest.getUser(), pendingRequest.getRunId(), pendingRequest.getSkipHealthchecks(), pendingRequest.getMessage()));

      nextInstanceNumber++;
    }

    return newTasks;
  }

  private Optional<Long> getNextRunAt(SingularityRequest request, RequestState state, SingularityDeployStatistics deployStatistics, PendingType pendingType,
    Optional<SingularityPendingDeploy> maybePendingDeploy) {
    final long now = System.currentTimeMillis();

    long nextRunAt = now;

    if (request.isScheduled()) {
      if (pendingType == PendingType.IMMEDIATE || pendingType == PendingType.RETRY) {
        LOG.info("Scheduling requested immediate run of {}", request.getId());
      } else {
        try {
          Date nextRunAtDate = null;
          Date scheduleFrom = null;

          if (request.getScheduleTypeSafe() == ScheduleType.RFC5545) {
            final RFC5545Schedule rfc5545Schedule = new RFC5545Schedule(request.getSchedule().get());
            nextRunAtDate = rfc5545Schedule.getNextValidTime();
            scheduleFrom = new Date(rfc5545Schedule.getStartDateTime().getMillis());
          } else {
            scheduleFrom = new Date(now);
            final CronExpression cronExpression = new CronExpression(request.getQuartzScheduleSafe());
            nextRunAtDate = cronExpression.getNextValidTimeAfter(scheduleFrom);
          }

          if (nextRunAtDate == null) {
            return Optional.absent();
          }

          LOG.trace("Calculating nextRunAtDate for {} (schedule: {}): {} (from: {})", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom);

          nextRunAt = Math.max(nextRunAtDate.getTime(), now); // don't create a schedule that is overdue as this is used to indicate that singularity is not fulfilling requests.

          LOG.trace("Scheduling next run of {} (schedule: {}) at {} (from: {})", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom);
        } catch (ParseException | InvalidRecurrenceRuleException pe) {
          throw Throwables.propagate(pe);
        }
      }
    }

    if (pendingType == PendingType.TASK_DONE && request.getWaitAtLeastMillisAfterTaskFinishesForReschedule().or(0L) > 0) {
      nextRunAt = Math.max(nextRunAt, now + request.getWaitAtLeastMillisAfterTaskFinishesForReschedule().get());

      LOG.trace("Adjusted next run of {} to {} (by {}) due to waitAtLeastMillisAfterTaskFinishesForReschedule", request.getId(), nextRunAt,
        JavaUtils.durationFromMillis(request.getWaitAtLeastMillisAfterTaskFinishesForReschedule().get()));
    }

    if (state == RequestState.SYSTEM_COOLDOWN && pendingType != PendingType.NEW_DEPLOY) {
      final long prevNextRunAt = nextRunAt;
      nextRunAt = Math.max(nextRunAt, now + TimeUnit.SECONDS.toMillis(configuration.getCooldownMinScheduleSeconds()));
      LOG.trace("Adjusted next run of {} to {} (from: {}) due to cooldown", request.getId(), nextRunAt, prevNextRunAt);
    }

    return Optional.of(nextRunAt);
  }

}
