package dapr4s.workflow

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Accessor (rung 2) for managing Dapr workflow instances (client-side), obtained argument-less via
  * [[DaprCapability.workflow]].
  *
  * The launch operations (`start*`) stay on the accessor because they '''mint''' the instance id; once you have an id,
  * [[apply]] narrows to a [[WorkflowInstanceCapability]] whose operations all target that one instance (the id is no
  * longer a per-call argument).
  */
@scala.caps.assumeSafe
trait AccessWorkflowCapability extends scala.caps.ExclusiveCapability:

  /** Start a new workflow instance. Returns the generated instance ID. */
  def start(name: WorkflowName): WorkflowInstanceId

  /** Start a new workflow instance with a typed input payload. Returns the generated instance ID. */
  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId

  /** Start a new workflow instance with a specific instance ID (no input). */
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId

  /** Start a new workflow instance with a specific instance ID and typed input. */
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId

  /** Obtain a [[WorkflowInstanceCapability]] scoped to an existing instance id. */
  def apply(instanceId: WorkflowInstanceId): WorkflowInstanceCapability^{this}

/** Capability scoped to a single workflow instance (client-side), reached via [[AccessWorkflowCapability.apply]]. Every
  * operation targets the bound [[instanceId]], so the id is no longer a per-call argument.
  */
@scala.caps.assumeSafe
trait WorkflowInstanceCapability extends scala.caps.ExclusiveCapability:
  val instanceId: WorkflowInstanceId

  /** Fetch the current status snapshot of this instance. Returns `None` if the instance does not exist. */
  def getStatus(): Option[WorkflowSnapshot]

  /** Suspend this running instance (can be resumed later). */
  def suspend(): Unit

  /** Resume this previously suspended instance. */
  def resume(): Unit

  /** Terminate this instance immediately. */
  def terminate(): Unit

  /** Send an external event to this waiting instance. */
  def raiseEvent[E: JsonCodec](eventName: EventName, payload: E): Unit

  /** Block until this instance completes (or the timeout expires). Returns the final snapshot, or `None` if the
    * instance was not found.
    */
  def waitForCompletion(timeout: FiniteDuration): Option[WorkflowSnapshot]

  /** Purge this instance's state from the state store. Returns `true` if purged. */
  def purge(): Boolean

/** Companion-object API for [[AccessWorkflowCapability]] — the launch operations.
  *
  * Forwards to the `AccessWorkflowCapability` in the enclosing `using` context:
  * {{{
  *   def processOrder(order: Order)(using AccessWorkflowCapability): WorkflowInstanceId =
  *     AccessWorkflowCapability.start[Order](
  *       WorkflowName(classOf[OrderWorkflow].getSimpleName),
  *       order,
  *     )
  * }}}
  *
  * Operations that target an existing instance (status, suspend, resume, terminate, raiseEvent, waitForCompletion,
  * purge) live on [[WorkflowInstanceCapability]] and are also available as fluent extension methods on
  * [[WorkflowInstanceId]] — e.g. `id.suspend()` — which read as a method on the instance.
  */
@scala.caps.assumeSafe
object AccessWorkflowCapability:
  def start(name: WorkflowName)(using cap: AccessWorkflowCapability): WorkflowInstanceId =
    cap.start(name)
  def start[I: JsonCodec](name: WorkflowName, input: I)(using
      cap: AccessWorkflowCapability,
  ): WorkflowInstanceId =
    cap.start(name, input)
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId)(using
      cap: AccessWorkflowCapability,
  ): WorkflowInstanceId =
    cap.startWithId(name, instanceId)
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I)(using
      cap: AccessWorkflowCapability,
  ): WorkflowInstanceId =
    cap.startWithId(name, instanceId, input)

/** Companion-object API for [[WorkflowInstanceCapability]] — the per-instance operations. Forwards to the
  * `WorkflowInstanceCapability` in the enclosing `using` context.
  */
@scala.caps.assumeSafe
object WorkflowInstanceCapability:
  def getStatus()(using cap: WorkflowInstanceCapability): Option[WorkflowSnapshot] =
    cap.getStatus()
  def suspend()(using cap: WorkflowInstanceCapability): Unit =
    cap.suspend()
  def resume()(using cap: WorkflowInstanceCapability): Unit =
    cap.resume()
  def terminate()(using cap: WorkflowInstanceCapability): Unit =
    cap.terminate()
  def raiseEvent[E: JsonCodec](eventName: EventName, payload: E)(using cap: WorkflowInstanceCapability): Unit =
    cap.raiseEvent(eventName, payload)
  def waitForCompletion(timeout: FiniteDuration)(using cap: WorkflowInstanceCapability): Option[WorkflowSnapshot] =
    cap.waitForCompletion(timeout)
  def purge()(using cap: WorkflowInstanceCapability): Boolean =
    cap.purge()

