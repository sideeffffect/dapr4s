package dapr.safe.internal

import dapr.safe.*
import unsafeExceptions.canThrowAny

/** Internal implementation wrapping an `io.dapr.durabletask.Task` and a deferred compute function.
  *
  * The `javaTask` reference is kept solely to delegate [[Task.isDone]] and [[Task.isCancelled]]. The actual value is
  * produced by `compute`, which calls `javaTask.await()` internally and then decodes or transforms the result.
  */
@scala.caps.assumeSafe
private[safe] final class TaskImpl[+O](
    private val javaTask: io.dapr.durabletask.Task[?],
    private val compute: () => O,
) extends Task[O]:
  def isDone: Boolean = javaTask.isDone()
  def isCancelled: Boolean = javaTask.isCancelled()
  def await(): O = compute()
  // asInstanceOf: f's captures flow into the lambda inside TaskImpl, but Task[U] is @assumeSafe
  // and always has an empty external capture set — the cast erases the capture annotation.
  def map[U](f: O => U): Task[U] = new TaskImpl(javaTask, () => f(compute())).asInstanceOf[Task[U]]

/** Wraps `io.dapr.workflows.WorkflowContext` (Java SDK) and exposes the Scala [[WorkflowContext]] trait. */
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
    val name = activityClass.getCanonicalName.nn
    val javaTask = ctx.callActivity(name, inputJson, classOf[String])
    val codec = summon[JsonCodec[O]]
    new TaskImpl(
      javaTask,
      () => {
        val result = javaTask.await()
        val json = if result == null then "null" else result.asInstanceOf[String]
        codec
          .decode(json)
          .getOrElse(throw RuntimeException(s"Failed to decode result of activity '$name'"))
      },
    )

  def callActivity[O: JsonCodec](activityClass: Class[? <: WorkflowActivity[Unit, O]]): Task[O] =
    val name = activityClass.getCanonicalName.nn
    val javaTask = ctx.callActivity(name, "null", classOf[String])
    val codec = summon[JsonCodec[O]]
    new TaskImpl(
      javaTask,
      () => {
        val result = javaTask.await()
        val json = if result == null then "null" else result.asInstanceOf[String]
        codec
          .decode(json)
          .getOrElse(throw RuntimeException(s"Failed to decode result of activity '$name'"))
      },
    )

  def createTimer(duration: java.time.Duration): Task[Unit] =
    val javaTask = ctx.createTimer(duration)
    new TaskImpl(javaTask, () => { javaTask.await(); () })

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: java.time.Duration): Task[T] =
    val javaTask = ctx.waitForExternalEvent(name.value, timeout, classOf[String])
    val codec = summon[JsonCodec[T]]
    new TaskImpl(
      javaTask,
      () => {
        val result = javaTask.await()
        val json = if result == null then "null" else result.asInstanceOf[String]
        codec
          .decode(json)
          .getOrElse(throw RuntimeException(s"Failed to decode external event '${name.value}'"))
      },
    )

  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T] =
    val javaTask = ctx.waitForExternalEvent(name.value, classOf[String])
    val codec = summon[JsonCodec[T]]
    new TaskImpl(
      javaTask,
      () => {
        val result = javaTask.await()
        val json = if result == null then "null" else result.asInstanceOf[String]
        codec
          .decode(json)
          .getOrElse(throw RuntimeException(s"Failed to decode external event '${name.value}'"))
      },
    )

  def complete[O: JsonCodec](output: O): Unit =
    ctx.complete(summon[JsonCodec[O]].encode(output))

  // ContinueAsNewInterruption thrown here must reach the runtime — do not catch it.
  def continueAsNew[I: JsonCodec](input: I): Unit =
    ctx.continueAsNew(summon[JsonCodec[I]].encode(input))

  def newUuid(): java.util.UUID =
    ctx.newUuid().nn
