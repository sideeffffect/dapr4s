package dapr4s

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.caps

// ---------------------------------------------------------------------------
// Task — a handle to a scheduled durable operation
// ---------------------------------------------------------------------------

/** A handle to a durable operation scheduled inside a [[Workflow]].
  *
  * Obtain instances from [[WorkflowContext]] methods (`callActivity`, `createTimer`, `waitForExternalEvent`). Call
  * [[await]] to block until the operation completes and get its result. Because the workflow runtime replays history on
  * restart, calling [[await]] during replay returns the cached result immediately without re-executing the work.
  *
  * A `Task` returned by those methods captures the [[WorkflowContext]] (`Task[O]^{ctx}`).  Since the context is an
  * [[scala.caps.ExclusiveCapability]] scoped to [[Workflow.run]], capture checking forbids a `Task` from outliving the
  * `run` block — it cannot be stored in an outer `var`, returned, or otherwise smuggled out and awaited later.  This is
  * the same `^{this}` lifetime-binding used by every sub-capability of [[DaprCapability]], and it mirrors the
  * underlying `io.dapr.durabletask.Task`, which is only meaningful while its orchestration is live.
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
    * The returned task captures both this task and `f`.  Since a [[Task]] from [[WorkflowContext]]
    * captures the enclosing context (see the class doc), the mapped task captures it too — so it
    * also cannot escape [[Workflow.run]].
    */
  def map[U](f: O => U): Task[U]^{this, f}

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
  *   - `io.dapr.durabletask.interruption.OrchestratorBlockedException` — emitted by [[Task.await]] when a task has not
  *     yet completed; the runtime catches it to suspend execution
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
    * `A` must be the concrete type of a [[WorkflowActivity]] subclass registered in the same [[DaprApp]].
    * The input is serialised with the activity's [[JsonCodec]] and the output is deserialised from the activity's
    * return value.
    */
  def callActivity[A](using d: ActivityDef[A])(input: d.Input): Task[d.Output]^{this}

  /** Overload for activities that take no input. */
  def callActivity[A](using d: ActivityDef[A], ev: d.Input =:= Unit): Task[d.Output]^{this}

  /** Schedule an activity by its registered [[ActivityName]] and return a [[Task]] that resolves to its output.
    *
    * Lower-level counterpart to `callActivity[A]`: the activity is identified by name rather than by its concrete
    * type, so it works with activities whose type is not nameable at the call site (e.g. those reified by
    * `dapr4s.derivation.WorkflowActivities`). The input is serialised with `JsonCodec[I]` and the output deserialised
    * with `JsonCodec[O]`; both must match the registered activity's codecs.
    */
  def callActivityByName[I: JsonCodec, O: JsonCodec](name: ActivityName, input: I): Task[O]^{this}

  /** Overload of the name-based scheduler for activities that take no input. */
  def callActivityByName[O: JsonCodec](name: ActivityName): Task[O]^{this}

  /** Create a durable timer that fires after `duration`. */
  def createTimer(duration: FiniteDuration): Task[Unit]^{this}

  /** Wait for an external event with the given name, up to `timeout`. The payload is deserialised with the provided
    * [[JsonCodec]].
    */
  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: FiniteDuration): Task[T]^{this}

  /** Wait for an external event with the given name (no timeout). */
  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T]^{this}

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
// WorkflowContext companion — forwarders for capability-style usage
// ---------------------------------------------------------------------------

/** Companion forwarders so user workflow code never names the [[WorkflowContext]] value.
  *
  * {{{
  *   class OrderWorkflow extends Workflow:
  *     def run(using WorkflowContext): Unit =
  *       val input = WorkflowContext.getInput[OrderRequest].getOrElse(throw RuntimeException("No input"))
  *       val paymentTask = WorkflowContext.callActivity[ProcessPaymentActivity](input)
  *       val result = paymentTask.await()
  *       WorkflowContext.complete(result)
  * }}}
  */
@scala.caps.assumeSafe
object WorkflowContext:

  def instanceId(using ctx: WorkflowContext): WorkflowInstanceId = ctx.instanceId

  def isReplaying(using ctx: WorkflowContext): Boolean = ctx.isReplaying

  def getInput[I: JsonCodec](using ctx: WorkflowContext): Option[I] = ctx.getInput[I]

  def callActivity[A](using d: ActivityDef[A])(input: d.Input)(using ctx: WorkflowContext): Task[d.Output]^{ctx} =
    ctx.callActivity(input)

  def callActivity[A](using d: ActivityDef[A], ev: d.Input =:= Unit)(using ctx: WorkflowContext): Task[d.Output]^{ctx} =
    ctx.callActivity

  def callActivityByName[I: JsonCodec, O: JsonCodec](name: ActivityName, input: I)(using
      ctx: WorkflowContext,
  ): Task[O]^{ctx} =
    ctx.callActivityByName[I, O](name, input)

  def callActivityByName[O: JsonCodec](name: ActivityName)(using ctx: WorkflowContext): Task[O]^{ctx} =
    ctx.callActivityByName[O](name)

  def createTimer(duration: FiniteDuration)(using ctx: WorkflowContext): Task[Unit]^{ctx} =
    ctx.createTimer(duration)

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: FiniteDuration)(using
      ctx: WorkflowContext,
  ): Task[T]^{ctx} =
    ctx.waitForExternalEvent(name, timeout)

  def waitForExternalEvent[T: JsonCodec](name: EventName)(using ctx: WorkflowContext): Task[T]^{ctx} =
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
  *       val paymentTask = WorkflowContext.callActivity[ProcessPaymentActivity](input)
  *       val result = paymentTask.await()
  *       WorkflowContext.complete(result)
  * }}}
  *
  * Workflows **must be deterministic** — use only [[WorkflowContext]] APIs for scheduling side effects.
  *
  * The workflow is identified by its simple class name when starting instances (this matches how the runtime registers
  * it with the sidecar):
  * {{{
  *   WorkflowCapability.start(WorkflowName(classOf[OrderWorkflow].getSimpleName))
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
    * `io.dapr.durabletask.interruption.ContinueAsNewInterruption` — both are control-flow signals used by the workflow
    * runtime and must propagate out of `run`.
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
  * An activity receives a [[DaprCapability]] on every invocation, so it can perform Dapr I/O
  * (call other services, read state, publish events) without capturing a capability in a field.
  * Because the capability arrives as a parameter and is never stored, activity implementations
  * stay capture-checked ("safe mode") — no `@scala.caps.assumeSafe` is needed:
  *
  * {{{
  *   class ProcessPaymentActivity extends WorkflowActivity[OrderRequest, PaymentResult]:
  *     def execute(input: OrderRequest)(using DaprCapability): PaymentResult =
  *       DaprCapability.invoke:
  *         InvokeCapability.invoke(PaymentService, InvokeMethodName("charge"), input)[PaymentResult]
  * }}}
  */
@scala.caps.assumeSafe
abstract class WorkflowActivity[I, O](using
    private[dapr4s] val inputCodec: JsonCodec[I],
    private[dapr4s] val outputCodec: JsonCodec[O],
):

  /** Wire name under which this activity is registered with the runtime and scheduled from workflows.
    *
    * Defaults to the simple class name (matching how workflows are named), which is what
    * [[WorkflowContext.callActivity callActivity[A]]] resolves via [[ActivityDef]]. As with every other name in this
    * library, registration is first-writer-wins, so two activity classes that share a simple name would collide —
    * override `activityName` to disambiguate. Activities reified by `dapr4s.derivation.WorkflowActivities` are anonymous
    * (no usable simple name), so they override this with a stable name derived from the implementation class and method,
    * matched by the corresponding name-based [[WorkflowContext.callActivityByName(name:* callActivityByName(name, …)]].
    */
  def activityName: String = getClass.getSimpleName.nn

  /** Implement activity logic here.  May perform I/O; need not be deterministic.
    *
    * The [[DaprCapability]] is supplied by the workflow runtime for the duration of the call;
    * use it (directly or via the `DaprCapability` transformer API) to reach any Dapr building
    * block. Do not store it — it must not outlive the call.
    */
  def execute(input: I)(using DaprCapability): O

// ---------------------------------------------------------------------------
// ActivityDef — typeclass linking an activity class to its I/O types
// ---------------------------------------------------------------------------

/** Typeclass that links a [[WorkflowActivity]] subclass `A` to its input/output types.
  *
  * Instances are synthesised automatically by the compiler for every [[WorkflowActivity]] subclass
  * that has `ClassTag[A]`, `JsonCodec[Input]`, and `JsonCodec[Output]` in scope. Users never
  * construct or name this type — the compiler resolves it when calling
  * [[WorkflowContext.callActivity]]:
  * {{{
  *   // The compiler finds ActivityDef[ProcessPaymentActivity] and resolves
  *   // d.Input = OrderRequest and d.Output = PaymentResult automatically.
  *   val task = WorkflowContext.callActivity[ProcessPaymentActivity](input)
  * }}}
  */
@scala.caps.assumeSafe
sealed abstract class ActivityDef[A]:
  type Input
  type Output
  private[dapr4s] def activityName: String
  private[dapr4s] def inputCodec: JsonCodec[Input]
  private[dapr4s] def outputCodec: JsonCodec[Output]

@scala.caps.assumeSafe
object ActivityDef:
  /** Auto-derives an [[ActivityDef]] for any `WorkflowActivity[I, O]` subclass with a `ClassTag`. */
  given derived[I, O, A <: WorkflowActivity[I, O]](using
      ct: ClassTag[A],
      ic: JsonCodec[I],
      oc: JsonCodec[O],
  ): ActivityDef[A] with
    type Input  = I
    type Output = O
    def activityName = ct.runtimeClass.getSimpleName.nn
    def inputCodec   = ic
    def outputCodec  = oc
