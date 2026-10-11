package dapr4s.workflow

import dapr4s.*
import scala.concurrent.duration.FiniteDuration

/** Universal capability for managing Dapr workflow instances (client-side): reaches **any** instance, so every
  * per-instance operation names its [[WorkflowInstanceId]]. Obtained argument-less via [[DaprCapability.workflow]].
  *
  * The capability family has four interfaces, all in this companion:
  *
  *   - [[WorkflowCapability]] — any instance, full authority (this trait; adds `start*`, `suspend`, `resume`,
  *     `terminate`, `raiseEvent`, `purge`)
  *   - [[WorkflowCapability.ReadOnly]] — any instance, observe only (`getStatus`, `waitForCompletion`)
  *   - [[WorkflowCapability.Instance]] — one instance, full authority
  *   - [[WorkflowCapability.Instance.ReadOnly]] — one instance, observe only
  *
  * The launch operations (`start*`) exist only on this universal trait because they '''mint''' the instance id; once
  * you have an id, [[instance]] narrows to a [[WorkflowCapability.Instance]] whose operations all target that one instance.
  * The two narrowings commute: `instance` on a [[WorkflowCapability.ReadOnly]] yields a
  * [[WorkflowCapability.Instance.ReadOnly]], so an observer cannot regain control by narrowing to an instance.
  */
@scala.caps.assumeSafe
trait WorkflowCapability extends WorkflowCapability.ReadOnly:
  /** Narrow to an existing instance id, keeping full authority. */
  override def instance(instanceId: WorkflowInstanceId): WorkflowCapability.Instance^{this}

  /** Start a new workflow instance. Returns the generated instance ID. */
  def start(name: WorkflowName): WorkflowInstanceId

  /** Start a new workflow instance with a typed input payload. Returns the generated instance ID. */
  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId

  /** Start a new workflow instance with a specific instance ID (no input). */
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId

  /** Start a new workflow instance with a specific instance ID and typed input. */
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId

  /** Suspend the running instance (can be resumed later). */
  def suspend(instanceId: WorkflowInstanceId): Unit

  /** Resume the previously suspended instance. */
  def resume(instanceId: WorkflowInstanceId): Unit

  /** Terminate the instance immediately. */
  def terminate(instanceId: WorkflowInstanceId): Unit

  /** Send an external event to the waiting instance. */
  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: EventName, payload: E): Unit

  /** Purge the instance's state from the state store. Returns `true` if purged. */
  def purge(instanceId: WorkflowInstanceId): Boolean

/** The [[WorkflowCapability]] family: the observe-only universal view, the instance-bound views, and the
  * companion-object forwarders for each.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def processOrder(order: Order)(using WorkflowCapability): WorkflowInstanceId =
  *     WorkflowCapability.start[Order](WorkflowName(classOf[OrderWorkflow].getSimpleName), order)
  *
  *   def cancel()(using WorkflowCapability.Instance): Unit =
  *     WorkflowCapability.Instance.terminate()
  * }}}
  */
@scala.caps.assumeSafe
object WorkflowCapability:

  /** Observe-only view of [[WorkflowCapability]]: reaches any instance, but cannot start or control one. */
  @scala.caps.assumeSafe
  trait ReadOnly extends scala.caps.ExclusiveCapability:
    /** Narrow to an existing instance id, keeping observe-only authority. */
    def instance(instanceId: WorkflowInstanceId): Instance.ReadOnly^{this}

    /** Fetch the current status snapshot of the instance. Returns `None` if the instance does not exist. */
    def getStatus(instanceId: WorkflowInstanceId): Option[WorkflowSnapshot]

    /** Block until the instance completes (or the timeout expires); see
      * [[WorkflowCapability.Instance.ReadOnly.waitForCompletion]].
      */
    def waitForCompletion(instanceId: WorkflowInstanceId, timeout: FiniteDuration): Option[WorkflowSnapshot]

  /** Forwarders for [[WorkflowCapability.ReadOnly]]. */
  @scala.caps.assumeSafe
  object ReadOnly:
    /** Narrow the observe-only capability in scope to one instance and return it. */
    def instance(instanceId: WorkflowInstanceId)(using cap: ReadOnly): Instance.ReadOnly^{cap} =
      cap.instance(instanceId)
    def getStatus(instanceId: WorkflowInstanceId)(using cap: ReadOnly): Option[WorkflowSnapshot] =
      cap.getStatus(instanceId)
    def waitForCompletion(instanceId: WorkflowInstanceId, timeout: FiniteDuration)(using
        cap: ReadOnly,
    ): Option[WorkflowSnapshot] =
      cap.waitForCompletion(instanceId, timeout)

  /** Capability bound to a single workflow instance (client-side), full authority. Reached via
    * [[WorkflowCapability.instance]]; every operation targets the bound [[instanceId]].
    */
  @scala.caps.assumeSafe
  trait Instance extends Instance.ReadOnly:
    /** Suspend this running instance (can be resumed later). */
    def suspend(): Unit

    /** Resume this previously suspended instance. */
    def resume(): Unit

    /** Terminate this instance immediately. */
    def terminate(): Unit

    /** Send an external event to this waiting instance. */
    def raiseEvent[E: JsonCodec](eventName: EventName, payload: E): Unit

    /** Purge this instance's state from the state store. Returns `true` if purged. */
    def purge(): Boolean

  /** [[WorkflowCapability.Instance]]'s observe-only view, and the forwarders for [[WorkflowCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:

    /** Capability bound to a single workflow instance, observe only. */
    @scala.caps.assumeSafe
    trait ReadOnly extends scala.caps.ExclusiveCapability:
      /** The workflow instance this capability is bound to. */
      val instanceId: WorkflowInstanceId

      /** Fetch the current status snapshot of this instance. Returns `None` if the instance does not exist. */
      def getStatus(): Option[WorkflowSnapshot]

      /** Block until this instance completes (or the timeout expires). Returns the final snapshot, or `None` if the
        * instance was not found.
        */
      def waitForCompletion(timeout: FiniteDuration): Option[WorkflowSnapshot]

    /** Forwarders for [[WorkflowCapability.Instance.ReadOnly]]. */
    @scala.caps.assumeSafe
    object ReadOnly:
      def getStatus()(using cap: ReadOnly): Option[WorkflowSnapshot] =
        cap.getStatus()
      def waitForCompletion(timeout: FiniteDuration)(using cap: ReadOnly): Option[WorkflowSnapshot] =
        cap.waitForCompletion(timeout)

    def getStatus()(using cap: Instance): Option[WorkflowSnapshot] =
      cap.getStatus()
    def waitForCompletion(timeout: FiniteDuration)(using cap: Instance): Option[WorkflowSnapshot] =
      cap.waitForCompletion(timeout)
    def suspend()(using cap: Instance): Unit =
      cap.suspend()
    def resume()(using cap: Instance): Unit =
      cap.resume()
    def terminate()(using cap: Instance): Unit =
      cap.terminate()
    def raiseEvent[E: JsonCodec](eventName: EventName, payload: E)(using cap: Instance): Unit =
      cap.raiseEvent(eventName, payload)
    def purge()(using cap: Instance): Boolean =
      cap.purge()

  /** Narrow the universal capability in scope to one instance and return it. */
  def instance(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Instance^{cap} =
    cap.instance(instanceId)
  def getStatus(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Option[WorkflowSnapshot] =
    cap.getStatus(instanceId)
  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: FiniteDuration)(using
      cap: WorkflowCapability,
  ): Option[WorkflowSnapshot] =
    cap.waitForCompletion(instanceId, timeout)
  def start(name: WorkflowName)(using cap: WorkflowCapability): WorkflowInstanceId =
    cap.start(name)
  def start[I: JsonCodec](name: WorkflowName, input: I)(using cap: WorkflowCapability): WorkflowInstanceId =
    cap.start(name, input)
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId)(using
      cap: WorkflowCapability,
  ): WorkflowInstanceId =
    cap.startWithId(name, instanceId)
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I)(using
      cap: WorkflowCapability,
  ): WorkflowInstanceId =
    cap.startWithId(name, instanceId, input)
  def suspend(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit =
    cap.suspend(instanceId)
  def resume(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit =
    cap.resume(instanceId)
  def terminate(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit =
    cap.terminate(instanceId)
  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: EventName, payload: E)(using
      cap: WorkflowCapability,
  ): Unit =
    cap.raiseEvent(instanceId, eventName, payload)
  def purge(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Boolean =
    cap.purge(instanceId)
