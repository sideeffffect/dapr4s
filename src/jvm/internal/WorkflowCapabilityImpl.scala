//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import dapr4s.workflow.*
import io.dapr.workflows.client.{DaprWorkflowClient, NewWorkflowOptions, WorkflowRuntimeStatus, WorkflowState}
import scala.concurrent.duration.FiniteDuration
import NullOps.*

@scala.caps.assumeSafe
private[internal] final class AccessWorkflowCapabilityImpl(
    private val client: DaprWorkflowClient,
) extends AccessWorkflowCapability:

  import WorkflowCapabilityImpl.*

  def start(name: WorkflowName): WorkflowInstanceId =
    WorkflowInstanceId(client.scheduleNewWorkflow(name.value).nn)

  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId =
    val node = toJsonNode(summon[JsonCodec[I]].encode(input))
    WorkflowInstanceId(client.scheduleNewWorkflow(name.value, node.asInstanceOf[Object]).nn)

  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId =
    val opts = new NewWorkflowOptions().setInstanceId(instanceId.value)
    WorkflowInstanceId(client.scheduleNewWorkflow(name.value, opts).nn)

  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId =
    val node = toJsonNode(summon[JsonCodec[I]].encode(input))
    val opts = new NewWorkflowOptions().setInstanceId(instanceId.value).setInput(node.asInstanceOf[Object])
    WorkflowInstanceId(client.scheduleNewWorkflow(name.value, opts).nn)

  def apply(instanceId: WorkflowInstanceId): WorkflowInstanceCapability^{this} =
    new WorkflowInstanceCapabilityImpl(client, instanceId).asInstanceOf[WorkflowInstanceCapability]

@scala.caps.assumeSafe
private[internal] final class WorkflowInstanceCapabilityImpl(
    private val client: DaprWorkflowClient,
    val instanceId: WorkflowInstanceId,
) extends WorkflowInstanceCapability:

  import WorkflowCapabilityImpl.*

  def getStatus(): Option[WorkflowSnapshot] =
    val state = client.getWorkflowState(instanceId.value, true)
    // The SDK returns a non-null WorkflowState even for unknown or purged instances; durabletask signals
    // "not found" by an empty workflow name (a scheduled instance always carries its orchestrator name).
    if state == null then None
    else
      val name = state.getName
      if name == null || name.isEmpty then None else Some(toSnapshot(state))

  def suspend(): Unit =
    client.suspendWorkflow(instanceId.value, null)

  def resume(): Unit =
    client.resumeWorkflow(instanceId.value, null)

  def terminate(): Unit =
    client.terminateWorkflow(instanceId.value, null)

  def raiseEvent[E: JsonCodec](eventName: EventName, payload: E): Unit =
    val jsonPayload: String = summon[JsonCodec[E]].encode(payload)
    client.raiseEvent(instanceId.value, eventName.value, jsonPayload.asInstanceOf[Object])

  def waitForCompletion(timeout: FiniteDuration): Option[WorkflowSnapshot] =
    val state = client.waitForWorkflowCompletion(instanceId.value, java.time.Duration.ofNanos(timeout.toNanos), true)
    if state == null then None else Some(toSnapshot(state))

  def purge(): Boolean =
    client.purgeWorkflow(instanceId.value)

@scala.caps.assumeSafe
private object WorkflowCapabilityImpl:
  private[internal] def toJsonNode(json: String): com.fasterxml.jackson.databind.JsonNode =
    Json.mapper.readTree(json)

  private[internal] def toSnapshot(state: WorkflowState): WorkflowSnapshot =
    WorkflowSnapshot(
      name = WorkflowName(state.getName.nn),
      instanceId = WorkflowInstanceId(state.getWorkflowId.nn),
      status = toStatus(state.getRuntimeStatus),
      createdAt = state.getCreatedAt.nn,
      lastUpdatedAt = state.getLastUpdatedAt.nn,
      serializedInput = state.getSerializedInput.toOption.map(SerializedJson(_)),
      serializedOutput = state.getSerializedOutput.toOption.map(SerializedJson(_)),
    )

  private def toStatus(rs: WorkflowRuntimeStatus | Null): WorkflowStatus =
    if rs == null then WorkflowStatus.Pending
    else
      rs match
        case WorkflowRuntimeStatus.RUNNING          => WorkflowStatus.Running
        case WorkflowRuntimeStatus.COMPLETED        => WorkflowStatus.Completed
        case WorkflowRuntimeStatus.CONTINUED_AS_NEW => WorkflowStatus.ContinuedAsNew
        case WorkflowRuntimeStatus.FAILED           => WorkflowStatus.Failed
        case WorkflowRuntimeStatus.CANCELED         => WorkflowStatus.Canceled
        case WorkflowRuntimeStatus.TERMINATED       => WorkflowStatus.Terminated
        case WorkflowRuntimeStatus.PENDING          => WorkflowStatus.Pending
        case WorkflowRuntimeStatus.SUSPENDED        => WorkflowStatus.Suspended
