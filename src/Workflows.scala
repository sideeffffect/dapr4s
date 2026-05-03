package dapr.safe

import unsafeExceptions.canThrowAny

// ---------------------------------------------------------------------------
// WorkflowTask — a handle to a scheduled durable operation
// ---------------------------------------------------------------------------

/** A handle to a durable operation scheduled inside a [[DaprWorkflow]].
  *
  * Obtain instances from [[WorkflowContext]] methods (`callActivity`, `createTimer`, `waitForExternalEvent`). Call
  * `await()` to block until the operation completes and get its result. This is safe inside a workflow because the
  * workflow runtime replays the history and skips already-completed tasks.
  */
@scala.caps.assumeSafe
final class WorkflowTask[+O] private[safe] (private val compute: () => O):
  def await(): O = compute()

// ---------------------------------------------------------------------------
// WorkflowContext — clean Scala wrapper over the Java workflow context
// ---------------------------------------------------------------------------

/** Context object provided to [[DaprWorkflow.run]].
  *
  * Mirrors the key methods of `io.dapr.workflows.WorkflowContext` but uses Scala types throughout — no Java types leak
  * into user code.
  *
  * Key design constraint: workflow logic **must be deterministic** (no I/O, random, or wall-clock time). All side
  * effects must be scheduled via the methods below and awaited via [[WorkflowTask.await]].
  */
@scala.caps.assumeSafe
trait WorkflowContext:

  /** The instance ID of the currently running workflow. */
  def instanceId: WorkflowInstanceId

  /** True while the workflow runtime is replaying previously executed steps. */
  def isReplaying: Boolean

  /** Deserialise the workflow input payload.  Returns `None` if no input was provided. */
  def getInput[I: JsonCodec]: Option[I]

  /** Schedule an activity and return a [[WorkflowTask]] that resolves to its output.
    *
    * `activityClass` must be the concrete class of a [[DaprActivity]] subclass registered in the same [[DaprApp]]. The
    * input is serialised with the activity's [[JsonCodec]] and the output is deserialised from the activity's return
    * value.
    */
  def callActivity[I: JsonCodec, O: JsonCodec](
      activityClass: Class[? <: DaprActivity[I, O]],
      input: I,
  ): WorkflowTask[O]

  /** Overload for activities that take no input. */
  def callActivity[O: JsonCodec](activityClass: Class[? <: DaprActivity[Unit, O]]): WorkflowTask[O]

  /** Create a durable timer that fires after `duration`. */
  def createTimer(duration: java.time.Duration): WorkflowTask[Unit]

  /** Wait for an external event with the given name, up to `timeout`. The payload is deserialised with the provided
    * [[JsonCodec]].
    */
  def waitForExternalEvent[T: JsonCodec](name: String, timeout: java.time.Duration): WorkflowTask[T]

  /** Wait for an external event with the given name (no timeout). */
  def waitForExternalEvent[T: JsonCodec](name: String): WorkflowTask[T]

  /** Complete the workflow instance with a serialisable output value. */
  def complete[O: JsonCodec](output: O): Unit

  /** Restart the workflow with new input, clearing its history. */
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
      activityClass: Class[? <: DaprActivity[I, O]],
      input: I,
  ): WorkflowTask[O] =
    val inputJson = summon[JsonCodec[I]].encode(input)
    val name = activityClass.getCanonicalName.nn
    val javaTask = ctx.callActivity(name, inputJson, classOf[String])
    val codec = summon[JsonCodec[O]]
    new WorkflowTask(() => {
      val result = javaTask.await()
      val json = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(
          throw RuntimeException(s"Failed to decode result of activity '$name'"),
        )
    })

  def callActivity[O: JsonCodec](activityClass: Class[? <: DaprActivity[Unit, O]]): WorkflowTask[O] =
    val name = activityClass.getCanonicalName.nn
    val javaTask = ctx.callActivity(name, "null", classOf[String])
    val codec = summon[JsonCodec[O]]
    new WorkflowTask(() => {
      val result = javaTask.await()
      val json = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(
          throw RuntimeException(s"Failed to decode result of activity '$name'"),
        )
    })

  def createTimer(duration: java.time.Duration): WorkflowTask[Unit] =
    val javaTask = ctx.createTimer(duration)
    new WorkflowTask(() => { javaTask.await(); () })

  def waitForExternalEvent[T: JsonCodec](name: String, timeout: java.time.Duration): WorkflowTask[T] =
    val javaTask = ctx.waitForExternalEvent(name, timeout, classOf[String])
    val codec = summon[JsonCodec[T]]
    new WorkflowTask(() => {
      val result = javaTask.await()
      val json = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(
          throw RuntimeException(s"Failed to decode external event '$name'"),
        )
    })

  def waitForExternalEvent[T: JsonCodec](name: String): WorkflowTask[T] =
    val javaTask = ctx.waitForExternalEvent(name, classOf[String])
    val codec = summon[JsonCodec[T]]
    new WorkflowTask(() => {
      val result = javaTask.await()
      val json = if result == null then "null" else result.asInstanceOf[String]
      codec
        .decode(json)
        .getOrElse(
          throw RuntimeException(s"Failed to decode external event '$name'"),
        )
    })

  def complete[O: JsonCodec](output: O): Unit =
    ctx.complete(summon[JsonCodec[O]].encode(output))

  def continueAsNew[I: JsonCodec](input: I): Unit =
    ctx.continueAsNew(summon[JsonCodec[I]].encode(input))

  def newUuid(): java.util.UUID =
    ctx.newUuid().nn

// ---------------------------------------------------------------------------
// DaprWorkflow — user-facing base class for workflow orchestrations
// ---------------------------------------------------------------------------

/** Base class for Dapr workflow orchestrations.
  *
  * Extend this class, implement [[run]], and register the instance in [[DaprApp.workflows]].
  *
  * {{{
  *   class OrderWorkflow extends DaprWorkflow:
  *     def run(ctx: WorkflowContext): Unit =
  *       val input = ctx.getInput[OrderRequest].getOrElse(throw RuntimeException("No input"))
  *       val paymentTask = ctx.callActivity(classOf[ProcessPaymentActivity], input)
  *       val result = paymentTask.await()
  *       ctx.complete(result)
  * }}}
  *
  * Workflows **must be deterministic** — use only `ctx` APIs for scheduling side effects.
  */
/** Base class for Dapr workflow orchestrations.
  *
  * Extend this class, implement [[run]], and register the instance in [[DaprApp.workflows]].
  *
  * {{{
  *   class OrderWorkflow extends DaprWorkflow:
  *     def run(ctx: WorkflowContext): Unit =
  *       val input = ctx.getInput[OrderRequest].getOrElse(throw RuntimeException("No input"))
  *       val paymentTask = ctx.callActivity(classOf[ProcessPaymentActivity], input)
  *       val result = paymentTask.await()
  *       ctx.complete(result)
  * }}}
  *
  * Workflows **must be deterministic** — use only `ctx` APIs for scheduling side effects.
  *
  * The workflow is identified by its canonical class name when starting instances:
  * {{{
  *   WorkflowCapability.start(WorkflowName(classOf[OrderWorkflow].getCanonicalName))
  * }}}
  */
@scala.caps.assumeSafe
abstract class DaprWorkflow:

  /** Implement workflow orchestration logic here.
    *
    * Called once per workflow instance start (and re-called during replay — use `ctx.isReplaying` if needed). Use
    * `ctx.callActivity`, `ctx.createTimer`, and `ctx.waitForExternalEvent` to schedule durable work; call
    * `ctx.complete` when done.
    */
  def run(ctx: WorkflowContext): Unit

// ---------------------------------------------------------------------------
// DaprActivity — user-facing base class for workflow activities
// ---------------------------------------------------------------------------

/** Base class for Dapr workflow activities.
  *
  * Activities perform I/O and real work; unlike orchestrations they do not need to be deterministic. The input type `I`
  * is deserialised from the payload sent by the orchestration; the output type `O` is serialised and returned to it.
  *
  * Register instances in [[DaprApp.activities]]; reference the concrete class in [[WorkflowContext.callActivity]].
  *
  * {{{
  *   class ProcessPaymentActivity extends DaprActivity[OrderRequest, PaymentResult]:
  *     def execute(input: OrderRequest): PaymentResult =
  *       // call payment gateway...
  *       PaymentResult("confirmed")
  * }}}
  */
@scala.caps.assumeSafe
abstract class DaprActivity[I, O](using
    private[safe] val inputCodec: JsonCodec[I],
    private[safe] val outputCodec: JsonCodec[O],
):

  /** Implement activity logic here.  May perform I/O; need not be deterministic. */
  def execute(input: I): O
