//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import dapr4s.workflow.*
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import JsInterop.parseJson
// The status TYPE comes from the deep module (types are erased — no import is emitted), but the
// VALUES are read off the "@dapr/dapr" root re-export: ScalablyTyped's deep-module specifiers
// are unresolvable under Node ESM — see the note in InvokeCapabilityImpl.
import dapr4styped.daprDapr.mod.{DaprWorkflowClient, WorkflowRuntimeStatus as SdkStatuses}
import dapr4styped.daprDapr.workflowClientWorkflowStateMod.WorkflowState
import dapr4styped.daprDapr.workflowRuntimeWorkflowRuntimeStatusMod.WorkflowRuntimeStatus

@scala.caps.assumeSafe
private[internal] final class AccessWorkflowCapabilityImpl(
    private val client: DaprWorkflowClient,
) extends AccessWorkflowCapability:

  def start(name: WorkflowName): WorkflowInstanceId =
    WorkflowInstanceId(JsAwait.await(client.scheduleNewWorkflow(name.value)))

  // Workflow inputs are passed as PARSED JS values: the vendored durabletask client JSON.stringify-s
  // the input (client.js scheduleNewOrchestration), producing single-encoded JSON on the wire —
  // the same as the JVM impl, which passes a parsed Jackson JsonNode for its serializer to encode once.
  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId =
    val value = parseJson(summon[JsonCodec[I]].encode(input))
    WorkflowInstanceId(JsAwait.await(client.scheduleNewWorkflow(name.value, value)))

  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId =
    // The `input: Unit` overload passes `undefined` for the input slot, exactly like the hand
    // facade's js.undefined — the vendored client then schedules the instance without an input.
    WorkflowInstanceId(JsAwait.await(client.scheduleNewWorkflow(name.value, (), instanceId.value)))

  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId =
    val value = parseJson(summon[JsonCodec[I]].encode(input))
    WorkflowInstanceId(JsAwait.await(client.scheduleNewWorkflow(name.value, value, instanceId.value)))

  def apply(instanceId: WorkflowInstanceId): WorkflowInstanceCapability^{this} =
    new WorkflowInstanceCapabilityImpl(client, instanceId).asInstanceOf[WorkflowInstanceCapability]

@scala.caps.assumeSafe
private[internal] final class WorkflowInstanceCapabilityImpl(
    private val client: DaprWorkflowClient,
    val instanceId: WorkflowInstanceId,
) extends WorkflowInstanceCapability:

  import WorkflowCapabilityImpl.*

  def getStatus(): Option[WorkflowSnapshot] =
    val state = JsAwait.await(client.getWorkflowState(instanceId.value, true))
    // Unknown instances come back as `undefined` (the vendored newOrchestrationState returns
    // nothing when the GetInstance response has exists = false) — unlike the Java SDK, which
    // returns a state object with an empty name. The empty-name guard is kept anyway so both
    // platforms agree should the JS SDK ever mirror the Java behaviour.
    state.toOption.filter(_.name.nonEmpty).map(toSnapshot)

  def suspend(): Unit =
    JsAwait.await(client.suspendWorkflow(instanceId.value)): Unit

  def resume(): Unit =
    JsAwait.await(client.resumeWorkflow(instanceId.value)): Unit

  def terminate(): Unit =
    JsAwait.await(client.terminateWorkflow(instanceId.value, null)): Unit

  // Unlike workflow inputs, the event payload is passed as the RAW JSON STRING: the vendored client
  // JSON.stringify-s it (client.js raiseOrchestrationEvent), producing a JSON string value whose
  // content is our document — DOUBLE encoding, which is exactly what the JVM impl puts on the wire
  // (it hands the encoded JSON String to the Java SDK, whose Jackson serializer encodes it again).
  // The server-side waitForEvent decodes symmetrically, so the platforms stay wire-compatible.
  def raiseEvent[E: JsonCodec](eventName: EventName, payload: E): Unit =
    val jsonPayload: String = summon[JsonCodec[E]].encode(payload)
    JsAwait.await(client.raiseEvent(instanceId.value, eventName.value, jsonPayload)): Unit

  def waitForCompletion(timeout: FiniteDuration): Option[WorkflowSnapshot] =
    // On timeout, the vendored client REJECTS with its TimeoutError (a bare `class TimeoutError
    // extends Error` constructed with message "TimeoutError" — workflow/internal/durabletask/
    // exception/timeout-error.js, raced against the gRPC call in waitForOrchestrationCompletion).
    // The JVM impl lets the Java SDK's java.util.concurrent.TimeoutException propagate to the
    // caller, so the rejection is translated to that exact exception type here — timeouts throw
    // (never None); None is reserved for "instance not found", matching the JVM.
    // The timeout is passed as fractional seconds (toMillis / 1000.0, NOT toSeconds, which would
    // truncate sub-second timeouts to 0): the vendored client multiplies by 1000 for setTimeout,
    // so millisecond precision survives — matching the JVM, which passes a full-precision Duration.
    val state =
      try JsAwait.await(client.waitForWorkflowCompletion(instanceId.value, true, timeout.toMillis.toDouble / 1000.0))
      catch
        case e @ js.JavaScriptException(error: js.Error) =>
          if isVendoredTimeout(error) then
            throw new java.util.concurrent.TimeoutException(
              s"workflow '${instanceId.value}' did not complete within $timeout",
            )
          else throw e
    state.toOption.map(toSnapshot)

  def purge(): Boolean =
    JsAwait.await(client.purgeWorkflow(instanceId.value))

@scala.caps.assumeSafe
private object WorkflowCapabilityImpl:

  /** Recognise the vendored durabletask `TimeoutError`: it carries the literal message "TimeoutError" and its class
    * name survives as `constructor.name` (the SDK ships unminified CommonJS, so the name is stable); checking either
    * marker keeps detection robust.
    */
  private[internal] def isVendoredTimeout(error: js.Error): Boolean =
    // WHAT: asInstanceOf to js.Dynamic for untyped property access.
    // WHY: `constructor.name` is not part of the js.Error facade.
    // WHY SAFE: js.Dynamic is the untyped view of any JS value (no runtime cast); every JS object
    // has a constructor with a (possibly empty) string name, and the result is only compared.
    error.message == "TimeoutError" ||
      error.asInstanceOf[js.Dynamic].selectDynamic("constructor").selectDynamic("name").toString == "TimeoutError"

  private[internal] def toSnapshot(state: WorkflowState): WorkflowSnapshot =
    WorkflowSnapshot(
      name = WorkflowName(state.name),
      instanceId = WorkflowInstanceId(state.instanceId),
      status = toStatus(state.runtimeStatus),
      createdAt = java.time.Instant.ofEpochMilli(state.createdAt.getTime().toLong),
      lastUpdatedAt = java.time.Instant.ofEpochMilli(state.lastUpdatedAt.getTime().toLong),
      serializedInput = state.serializedInput.toOption.map(SerializedJson(_)),
      serializedOutput = state.serializedOutput.toOption.map(SerializedJson(_)),
    )

  /** Numeric `WorkflowRuntimeStatus` → [[WorkflowStatus]], mirroring the JVM impl's mapping. The JS enum has no
    * CANCELED member (the JVM maps CANCELED → Canceled; a cancelled-status protobuf value would crash inside the JS
    * SDK's own `fromOrchestrationStatus` before reaching us), and unknown values fall back to Pending exactly like the
    * JVM maps a `null` status. The comparisons read the values off the real SDK enum object (ScalablyTyped imports them
    * from the SDK module), so a renumbering upstream cannot silently corrupt the mapping.
    */
  private def toStatus(rs: WorkflowRuntimeStatus): WorkflowStatus =
    if rs == SdkStatuses.RUNNING then WorkflowStatus.Running
    else if rs == SdkStatuses.COMPLETED then WorkflowStatus.Completed
    else if rs == SdkStatuses.CONTINUED_AS_NEW then WorkflowStatus.ContinuedAsNew
    else if rs == SdkStatuses.FAILED then WorkflowStatus.Failed
    else if rs == SdkStatuses.TERMINATED then WorkflowStatus.Terminated
    else if rs == SdkStatuses.PENDING then WorkflowStatus.Pending
    else if rs == SdkStatuses.SUSPENDED then WorkflowStatus.Suspended
    else WorkflowStatus.Pending
