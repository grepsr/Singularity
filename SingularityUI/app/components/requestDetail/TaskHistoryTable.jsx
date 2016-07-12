import React, { PropTypes } from 'react';
import { connect } from 'react-redux';

import Utils from '../../utils';

import { FetchTaskHistoryForRequest } from '../../actions/api/history';

import ServerSideTable from '../common/ServerSideTable';
import JSONButton from '../common/JSONButton';

const TaskHistoryTable = ({requestId, tasks}) => {
  return (
    <div>
      <h2>Task history</h2>
      <ServerSideTable
        emptyMessage="No tasks"
        entries={tasks}
        paginate={tasks.length >= 5}
        perPage={5}
        fetchAction={FetchTaskHistoryForRequest}
        fetchParams={[requestId]}
        headers={['Name', 'Last State', 'Started', 'Updated', '', '']}
        renderTableRow={(data, index) => {
          return (
            <tr key={index}>
              <td><a href={`${config.appRoot}/task/${data.taskId.id}`}>{data.taskId.id}</a></td>
              <td><span className={`label label-${Utils.getLabelClassFromTaskState(data.lastTaskState)}`}>{Utils.humanizeText(data.lastTaskState)}</span></td>
              <td>{Utils.timestampFromNow(data.taskId.startedAt)}</td>
              <td>{Utils.timestampFromNow(data.updatedAt)}</td>
              <td className="actions-column"><a href={`${config.appRoot}/request/${data.taskId.requestId}/tail/${config.finishedTaskLogPath}?taskIds=${data.taskId.id}`} title="Log">&middot;&middot;&middot;</a></td>
              <td className="actions-column"><JSONButton object={data}>{'{ }'}</JSONButton></td>
            </tr>
          );
        }}
      />
    </div>
  );
};

TaskHistoryTable.propTypes = {
  requestId: PropTypes.string.isRequired,
  tasks: PropTypes.arrayOf(PropTypes.object).isRequired
};

const mapStateToProps = (state) => ({
  tasks: state.api.taskHistoryForRequest.data
});

export default connect(
  mapStateToProps,
  null
)(TaskHistoryTable);
