package dapr.safe.internal

import dapr.safe.*
import unsafeExceptions.canThrowAny

@scala.caps.assumeSafe
private[safe] final class TaskJson[+O](
    private val javaTask: io.dapr.durabletask.Task[String],
    private val error: String,
)(using codec: JsonCodec[O])
    extends Task[O]:
  def isDone: Boolean = javaTask.isDone()
  def isCancelled: Boolean = javaTask.isCancelled()
  def await(): O = {
    val result = javaTask.await()
    val json = if result == null then "null" else result
    codec
      .decode(json)
      .getOrElse(throw RuntimeException(error))
  }
  // Task[U] is @assumeSafe (empty capture set); cast erases f's captures from TaskMap.
  def map[U](f: O => U): Task[U] = new TaskMap(this, f).asInstanceOf[Task[U]]

@scala.caps.assumeSafe
private[safe] final class TaskUnit(
    private val javaTask: io.dapr.durabletask.Task[Void],
) extends Task[Unit]:
  def isDone: Boolean = javaTask.isDone()
  def isCancelled: Boolean = javaTask.isCancelled()
  def await(): Unit = javaTask.await()
  // Task[U] is @assumeSafe (empty capture set); cast erases f's captures from TaskMap.
  def map[U](f: Unit => U): Task[U] = new TaskMap(this, f).asInstanceOf[Task[U]]

@scala.caps.assumeSafe
private[safe] final class TaskMap[O1, +O](
    private val task: Task[O1],
    private val f: O1 => O,
) extends Task[O]:
  def isDone: Boolean = task.isDone
  def isCancelled: Boolean = task.isCancelled
  def await(): O = f(task.await())
  // this.f is in this's capture set; both casts erase captures so Task[U]'s empty set is satisfied.
  def map[U](f: O => U): Task[U] = new TaskMap[O, U](this.asInstanceOf[Task[O]], f).asInstanceOf[Task[U]]

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
    new TaskJson[O](javaTask, s"Failed to decode result of activity '$name'")

  def callActivity[O: JsonCodec](activityClass: Class[? <: WorkflowActivity[Unit, O]]): Task[O] =
    val name = activityClass.getCanonicalName.nn
    val javaTask = ctx.callActivity(name, "null", classOf[String])
    new TaskJson[O](javaTask, s"Failed to decode result of activity '$name'")

  def createTimer(duration: java.time.Duration): Task[Unit] =
    val javaTask = ctx.createTimer(duration)
    new TaskUnit(javaTask)

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: java.time.Duration): Task[T] =
    val javaTask = ctx.waitForExternalEvent(name.value, timeout, classOf[String])
    new TaskJson[T](javaTask, s"Failed to decode external event '${name.value}'")

  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T] =
    val javaTask = ctx.waitForExternalEvent(name.value, classOf[String])
    new TaskJson[T](javaTask, s"Failed to decode external event '${name.value}'")

  def complete[O: JsonCodec](output: O): Unit =
    ctx.complete(summon[JsonCodec[O]].encode(output))

  // ContinueAsNewInterruption thrown here must reach the runtime — do not catch it.
  def continueAsNew[I: JsonCodec](input: I): Unit =
    ctx.continueAsNew(summon[JsonCodec[I]].encode(input))

  def newUuid(): java.util.UUID =
    ctx.newUuid().nn
