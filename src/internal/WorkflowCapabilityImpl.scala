package dapr.safe.internal

import dapr.safe.*
import io.dapr.workflows.client.{DaprWorkflowClient, NewWorkflowOptions, WorkflowRuntimeStatus, WorkflowState}
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny
import java.util.concurrent.TimeoutException as JavaTimeoutException
import scala.util.control.NonFatal

@scala.caps.assumeSafe
private[safe] final class WorkflowCapabilityImpl(
    private val client: DaprWorkflowClient,
) extends WorkflowCapability:

  def start(name: WorkflowName): WorkflowInstanceId throws DaprWorkflowException =
    wrap(name, "start"):
      WorkflowInstanceId(client.scheduleNewWorkflow(name.value).nn)

  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId throws DaprWorkflowException =
    wrap(name, "start"):
      val jsonInput: String = summon[JsonCodec[I]].encode(input)
      WorkflowInstanceId(client.scheduleNewWorkflow(name.value, jsonInput.asInstanceOf[Object]).nn)

  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId throws DaprWorkflowException =
    wrap(name, "startWithId"):
      val opts = new NewWorkflowOptions().setInstanceId(instanceId.value)
      WorkflowInstanceId(client.scheduleNewWorkflow(name.value, opts).nn)

  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId throws
    DaprWorkflowException =
    wrap(name, "startWithId"):
      val jsonInput: String = summon[JsonCodec[I]].encode(input)
      val opts = new NewWorkflowOptions().setInstanceId(instanceId.value).setInput(jsonInput.asInstanceOf[Object])
      WorkflowInstanceId(client.scheduleNewWorkflow(name.value, opts).nn)

  def getStatus(instanceId: WorkflowInstanceId): Option[WorkflowSnapshot] throws DaprWorkflowException =
    wrapId(instanceId, "getStatus"):
      val state = client.getWorkflowState(instanceId.value, true)
      if state == null then None else Some(toSnapshot(state))

  def suspend(instanceId: WorkflowInstanceId): Unit throws DaprWorkflowException =
    wrapId(instanceId, "suspend"):
      client.suspendWorkflow(instanceId.value, null)

  def resume(instanceId: WorkflowInstanceId): Unit throws DaprWorkflowException =
    wrapId(instanceId, "resume"):
      client.resumeWorkflow(instanceId.value, null)

  def terminate(instanceId: WorkflowInstanceId): Unit throws DaprWorkflowException =
    wrapId(instanceId, "terminate"):
      client.terminateWorkflow(instanceId.value, null)

  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: String, payload: E): Unit throws
    DaprWorkflowException =
    wrapId(instanceId, "raiseEvent"):
      val jsonPayload: String = summon[JsonCodec[E]].encode(payload)
      client.raiseEvent(instanceId.value, eventName, jsonPayload.asInstanceOf[Object])

  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: java.time.Duration): Option[WorkflowSnapshot] throws
    DaprWorkflowException =
    try
      val state = client.waitForWorkflowCompletion(instanceId.value, timeout, true)
      if state == null then None else Some(toSnapshot(state))
    catch
      case _: JavaTimeoutException =>
        throw DaprWorkflowException(s"Timed out waiting for workflow '${instanceId.value}' to complete")
      case NonFatal(e: Exception) =>
        throw DaprWorkflowException(s"waitForCompletion failed for '${instanceId.value}': ${e.getMessage}", e)

  def purge(instanceId: WorkflowInstanceId): Boolean throws DaprWorkflowException =
    wrapId(instanceId, "purge"):
      client.purgeWorkflow(instanceId.value)

  private inline def wrap[T](name: WorkflowName, op: String)(body: => T): T throws DaprWorkflowException =
    try body
    catch
      case e: DaprWorkflowException => throw e
      case NonFatal(e: Exception)   =>
        throw DaprWorkflowException(s"Workflow '$op' failed for '${name.value}': ${e.getMessage}", e)

  private inline def wrapId[T](instanceId: WorkflowInstanceId, op: String)(body: => T): T throws DaprWorkflowException =
    try body
    catch
      case e: DaprWorkflowException => throw e
      case NonFatal(e: Exception)   =>
        throw DaprWorkflowException(s"Workflow '$op' failed for instance '${instanceId.value}': ${e.getMessage}", e)

  private def toSnapshot(state: WorkflowState): WorkflowSnapshot =
    WorkflowSnapshot(
      name = WorkflowName(state.getName.nn),
      instanceId = WorkflowInstanceId(state.getWorkflowId.nn),
      status = toStatus(state.getRuntimeStatus),
      createdAt = state.getCreatedAt.nn,
      lastUpdatedAt = state.getLastUpdatedAt.nn,
      serializedInput = Option(state.getSerializedInput),
      serializedOutput = Option(state.getSerializedOutput),
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
