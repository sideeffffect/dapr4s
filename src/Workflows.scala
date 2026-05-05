package dapr.safe

import unsafeExceptions.canThrowAny

// ---------------------------------------------------------------------------
// Task — a handle to a scheduled durable operation
// ---------------------------------------------------------------------------

/** A handle to a durable operation scheduled inside a [[Workflow]].
  *
  * Obtain instances from [[WorkflowContext]] methods (`callActivity`, `createTimer`, `waitForExternalEvent`). Call
  * [[await]] to block until the operation completes and get its result. Because the workflow runtime replays history
  * on restart, calling [[await]] during replay returns the cached result immediately without re-executing the work.
  *
  * The interface mirrors `io.dapr.durabletask.Task` but replaces the Java-style `thenApply`/`thenAccept` combinators
  * with the idiomatic Scala [[map]].
  *
  * '''Control-flow exceptions''' — never catch these inside workflow logic; they are runtime signals that must
  * propagate freely:
  *   - `io.dapr.durabletask.interruption.OrchestratorBlockedException` — suspends the orchestrator while it awaits an
  *     incomplete task
  *   - `io.dapr.durabletask.interruption.ContinueAsNewInterruption` — unwinds the call stack when
  *     [[WorkflowContext.continueAsNew]] restarts the workflow
  */
@scala.caps.assumeSafe
trait Task[+O]:

  /** True if the underlying durable operation has already completed. */
  def isDone: Boolean

  /** True if the underlying durable operation was cancelled. */
  def isCancelled: Boolean

  /** Block until the operation completes and return its result.
    *
    * Safe to call inside [[Workflow.run]] — the runtime replays history so a call during re-execution returns the
    * cached result without re-scheduling any work.
    */
  def await(): O

  /** Transform the result of this task without scheduling a new durable operation.
    *
    * `f` runs synchronously in the calling thread when [[await]] is called on the returned task.
    */
  def map[U](f: O => U): Task[U]

// ---------------------------------------------------------------------------
// TaskImpl — internal implementation of Task
// ---------------------------------------------------------------------------

/** Internal implementation wrapping an `io.dapr.durabletask.Task` and a deferred compute function.
  *
  * The `javaTask` reference is kept solely to delegate [[isDone]] and [[isCancelled]]. The actual value is produced by
  * `compute`, which calls `javaTask.await()` internally and then decodes or transforms the result.
  */
@scala.caps.assumeSafe
private[safe] final class TaskImpl[+O](
    private val javaTask: io.dapr.durabletask.Task[?],
    private val compute: () => O,
) extends Task[O]:
  def isDone: Boolean      = javaTask.isDone()
  def isCancelled: Boolean = javaTask.isCancelled()
  def await(): O           = compute()
  // @assumeSafe on the method body because `f`'s captures flow into the new TaskImpl, but
  // Task[U] (and TaskImpl, being @assumeSafe) is always treated as pure from the outside.
  @scala.caps.assumeSafe
  def map[U](f: O => U): Task[U] = new TaskImpl(javaTask, () => f(compute()))

// ---------------------------------------------------------------------------
// WorkflowContext — clean Scala wrapper over the Java workflow context
// ---------------------------------------------------------------------------

/** Context object provided to [[Workflow.run]].
  *
  * Mirrors the key methods of `io.dapr.workflows.WorkflowContext` but uses Scala types throughout — no Java types leak
  * into user code.
  *
  * Key design constraint: workflow logic **must be deterministic** (no I/O, random, or wall-clock time). All side
  * effects must be scheduled via the methods below and awaited via [[Task.await]].
  *
  * '''Control-flow exceptions''' — never catch inside workflow logic:
  *   - `io.dapr.durabletask.interruption.OrchestratorBlockedException` — emitted by [[Task.await]] when a task has
  *     not yet completed; the runtime catches it to suspend execution
  *   - `io.dapr.durabletask.interruption.ContinueAsNewInterruption` — thrown by [[continueAsNew]]; must reach the
  *     runtime to trigger the restart
  */
@scala.caps.assumeSafe
trait WorkflowContext extends scala.caps.ExclusiveCapability:

  /** The instance ID of the currently running workflow. */
  def instanceId: WorkflowInstanceId

  /** True while the workflow runtime is replaying previously executed steps. */
  def isReplaying: Boolean

  /** Deserialise the workflow input payload.  Returns `None` if no input was provided. */
  def getInput[I: JsonCodec]: Option[I]

  /** Schedule an activity and return a [[Task]] that resolves to its output.
    *
    * `activityClass` must be the concrete class of a [[WorkflowActivity]] subclass registered in the same [[DaprApp]].
    * The input is serialised with the activity's [[JsonCodec]] and the output is deserialised from the activity's
    * return value.
    */
  def callActivity[I: JsonCodec, O: JsonCodec](
      activityClass: Class[? <: WorkflowActivity[I, O]],
      input: I,
  ): Task[O]

  /** Overload for activities that take no input. */
  def callActivity[O: JsonCodec](activityClass: Class[? <: WorkflowActivity[Unit, O]]): Task[O]

  /** Create a durable timer that fires after `duration`. */
  def createTimer(duration: java.time.Duration): Task[Unit]

  /** Wait for an external event with the given name, up to `timeout`. The payload is deserialised with the provided
    * [[JsonCodec]].
    */
  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: java.time.Duration): Task[T]

  /** Wait for an external event with the given name (no timeout). */
  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T]

  /** Complete the workflow instance with a serialisable output value. */
  def complete[O: JsonCodec](output: O): Unit

  /** Restart the workflow with new input, clearing its history.
    *
    * Throws `io.dapr.durabletask.interruption.ContinueAsNewInterruption` — do not catch it.
    */
  def continueAsNew[I: JsonCodec](input: I): Unit

  /** Generate a UUID that is stable across replays (deterministic). */
  def newUuid(): java.util.UUID

// ---------------------------------------------------------------------------
// Internal WorkflowContextImpl — wraps io.dapr.workflows.WorkflowContext
// ---------------------------------------------------------------------------

@scala.caps.assumeSafe
private[safe] final class WorkflowContextImpl(
    private val ctx: io.dapr.workflows.WorkflowContext,
) extends WorkflowContext:

  def instanceId: WorkflowInstanceId =
    WorkflowInstanceId(ctx.getInstanceId.nn)

  def isReplaying: Boolean = ctx.isReplaying

  def getInput[I: JsonCodec]: Option[I] =
    Option(ctx.getInput(classOf[String])).flatMap { json =>
      summon[JsonCodec[I]].decode(json).toOption
    }

  def callActivity[I: JsonCodec, O: JsonCodec](
      activityClass: Class[? <: WorkflowActivity[I, O]],
      input: I,
  ): Task[O] =
    val inputJson = summon[JsonCodec[I]].encode(input)
    val name      = activityClass.getCanonicalName.nn
    val javaTask  = ctx.callActivity(name, inputJson, classOf[String])
    val codec     = summon[JsonCodec[O]]
    new TaskImpl(javaTask, () => {
      val result = javaTask.await()
      val json   = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(throw RuntimeException(s"Failed to decode result of activity '$name'"))
    })

  def callActivity[O: JsonCodec](activityClass: Class[? <: WorkflowActivity[Unit, O]]): Task[O] =
    val name     = activityClass.getCanonicalName.nn
    val javaTask = ctx.callActivity(name, "null", classOf[String])
    val codec    = summon[JsonCodec[O]]
    new TaskImpl(javaTask, () => {
      val result = javaTask.await()
      val json   = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(throw RuntimeException(s"Failed to decode result of activity '$name'"))
    })

  def createTimer(duration: java.time.Duration): Task[Unit] =
    val javaTask = ctx.createTimer(duration)
    new TaskImpl(javaTask, () => { javaTask.await(); () })

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: java.time.Duration): Task[T] =
    val javaTask = ctx.waitForExternalEvent(name.value, timeout, classOf[String])
    val codec    = summon[JsonCodec[T]]
    new TaskImpl(javaTask, () => {
      val result = javaTask.await()
      val json   = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(throw RuntimeException(s"Failed to decode external event '${name.value}'"))
    })

  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T] =
    val javaTask = ctx.waitForExternalEvent(name.value, classOf[String])
    val codec    = summon[JsonCodec[T]]
    new TaskImpl(javaTask, () => {
      val result = javaTask.await()
      val json   = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(throw RuntimeException(s"Failed to decode external event '${name.value}'"))
    })

  def complete[O: JsonCodec](output: O): Unit =
    ctx.complete(summon[JsonCodec[O]].encode(output))

  // ContinueAsNewInterruption thrown here must reach the runtime — do not catch it.
  def continueAsNew[I: JsonCodec](input: I): Unit =
    ctx.continueAsNew(summon[JsonCodec[I]].encode(input))

  def newUuid(): java.util.UUID =
    ctx.newUuid().nn

// ---------------------------------------------------------------------------
// WorkflowContext companion — forwarders for capability-style usage
// ---------------------------------------------------------------------------

/** Companion forwarders so user workflow code never names the [[WorkflowContext]] value.
  *
  * {{{
  *   class OrderWorkflow extends Workflow:
  *     def run(using WorkflowContext): Unit =
  *       val input = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("No input"))
  *       val paymentTask = WorkflowContext.callActivity(classOf[ProcessPaymentActivity], input)
  *       val result = paymentTask.await()
  *       WorkflowContext.complete(result)
  * }}}
  */
@scala.caps.assumeSafe
object WorkflowContext:

  def instanceId(using ctx: WorkflowContext): WorkflowInstanceId = ctx.instanceId

  def isReplaying(using ctx: WorkflowContext): Boolean = ctx.isReplaying

  def getInput[I: JsonCodec](using ctx: WorkflowContext): Option[I] = ctx.getInput[I]

  def callActivity[I: JsonCodec, O: JsonCodec](
      activityClass: Class[? <: WorkflowActivity[I, O]],
      input: I,
  )(using ctx: WorkflowContext): Task[O] = ctx.callActivity(activityClass, input)

  def callActivity[O: JsonCodec](
      activityClass: Class[? <: WorkflowActivity[Unit, O]],
  )(using ctx: WorkflowContext): Task[O] = ctx.callActivity(activityClass)

  def createTimer(duration: java.time.Duration)(using ctx: WorkflowContext): Task[Unit] =
    ctx.createTimer(duration)

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: java.time.Duration)(using
      ctx: WorkflowContext,
  ): Task[T] =
    ctx.waitForExternalEvent(name, timeout)

  def waitForExternalEvent[T: JsonCodec](name: EventName)(using ctx: WorkflowContext): Task[T] =
    ctx.waitForExternalEvent(name)

  def complete[O: JsonCodec](output: O)(using ctx: WorkflowContext): Unit = ctx.complete(output)

  def continueAsNew[I: JsonCodec](input: I)(using ctx: WorkflowContext): Unit = ctx.continueAsNew(input)

  def newUuid()(using ctx: WorkflowContext): java.util.UUID = ctx.newUuid()

// ---------------------------------------------------------------------------
// Workflow — user-facing base class for workflow orchestrations
// ---------------------------------------------------------------------------

/** Base class for Dapr workflow orchestrations.
  *
  * Extend this class, implement [[run]], and register the instance in [[DaprApp.workflows]].
  *
  * {{{
  *   class OrderWorkflow extends Workflow:
  *     def run(using WorkflowContext): Unit =
  *       val input = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("No input"))
  *       val paymentTask = WorkflowContext.callActivity(classOf[ProcessPaymentActivity], input)
  *       val result = paymentTask.await()
  *       WorkflowContext.complete(result)
  * }}}
  *
  * Workflows **must be deterministic** — use only [[WorkflowContext]] APIs for scheduling side effects.
  *
  * The workflow is identified by its canonical class name when starting instances:
  * {{{
  *   WorkflowCapability.start(WorkflowName(classOf[OrderWorkflow].getCanonicalName))
  * }}}
  */
@scala.caps.assumeSafe
abstract class Workflow:

  /** Implement workflow orchestration logic here.
    *
    * Called once per workflow instance start (and re-called during replay — use `WorkflowContext.isReplaying` if
    * needed). Use `WorkflowContext.callActivity`, `WorkflowContext.createTimer`, and
    * `WorkflowContext.waitForExternalEvent` to schedule durable work; call `WorkflowContext.complete` when done.
    *
    * Never catch `io.dapr.durabletask.interruption.OrchestratorBlockedException` or
    * `io.dapr.durabletask.interruption.ContinueAsNewInterruption` — both are control-flow signals used by the
    * workflow runtime and must propagate out of `run`.
    */
  def run(using WorkflowContext): Unit

// ---------------------------------------------------------------------------
// WorkflowActivity — user-facing base class for workflow activities
// ---------------------------------------------------------------------------

/** Base class for Dapr workflow activities.
  *
  * Activities perform I/O and real work; unlike orchestrations they do not need to be deterministic. The input type `I`
  * is deserialised from the payload sent by the orchestration; the output type `O` is serialised and returned to it.
  *
  * Register instances in [[DaprApp.activities]]; reference the concrete class in [[WorkflowContext.callActivity]].
  *
  * {{{
  *   class ProcessPaymentActivity extends WorkflowActivity[OrderRequest, PaymentResult]:
  *     def execute(input: OrderRequest): PaymentResult =
  *       // call payment gateway...
  *       PaymentResult("confirmed")
  * }}}
  */
@scala.caps.assumeSafe
abstract class WorkflowActivity[I, O](using
    private[safe] val inputCodec: JsonCodec[I],
    private[safe] val outputCodec: JsonCodec[O],
):

  /** Implement activity logic here.  May perform I/O; need not be deterministic. */
  def execute(input: I): O
