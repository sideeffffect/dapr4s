package dapr4s.workflow

import dapr4s.*

import language.experimental.safe

/** Runtime status of a Dapr workflow instance.
  *
  *   - [[WorkflowStatus.Running]] — executing normally; may be waiting for an activity, timer, or external event.
  *   - [[WorkflowStatus.Completed]] — finished successfully; output is available via
  *     [[WorkflowSnapshot.serializedOutput]].
  *   - [[WorkflowStatus.ContinuedAsNew]] — restarted with new input via [[WorkflowContext.continueAsNew]]; history
  *     cleared.
  *   - [[WorkflowStatus.Failed]] — terminated due to an unhandled exception in workflow logic.
  *   - [[WorkflowStatus.Canceled]] — cancelled by the runtime or via an explicit API call.
  *   - [[WorkflowStatus.Terminated]] — forcibly stopped via [[WorkflowInstanceCapability.terminate]].
  *   - [[WorkflowStatus.Pending]] — scheduled but not yet started (placement in progress).
  *   - [[WorkflowStatus.Suspended]] — paused via [[WorkflowInstanceCapability.suspend]]; resumes via
  *     [[WorkflowInstanceCapability.resume]].
  */
enum WorkflowStatus:
  case Running
  case Completed
  case ContinuedAsNew
  case Failed
  case Canceled
  case Terminated
  case Pending
  case Suspended

/** A point-in-time snapshot of a workflow instance's state.
  *
  * Returned by [[WorkflowInstanceCapability.getStatus]] and [[WorkflowInstanceCapability.waitForCompletion]].
  *
  * @param name
  *   The [[WorkflowName]] (simple class name) that identifies the workflow type.
  * @param instanceId
  *   The unique [[WorkflowInstanceId]] of this instance.
  * @param status
  *   Current [[WorkflowStatus]] of the instance.
  * @param createdAt
  *   When the instance was created (UTC).
  * @param lastUpdatedAt
  *   When the instance last changed state (UTC).
  * @param serializedInput
  *   The JSON-encoded workflow input, if one was provided at start.
  * @param serializedOutput
  *   The JSON-encoded workflow output set by [[WorkflowContext.complete]], if completed.
  */
final case class WorkflowSnapshot(
    name: WorkflowName,
    instanceId: WorkflowInstanceId,
    status: WorkflowStatus,
    createdAt: java.time.Instant,
    lastUpdatedAt: java.time.Instant,
    serializedInput: Option[SerializedJson],
    serializedOutput: Option[SerializedJson],
)
