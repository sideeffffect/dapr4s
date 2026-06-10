//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import scala.concurrent.duration.FiniteDuration
import unsafeExceptions.canThrowAny

@scala.caps.assumeSafe
private[internal] final class TaskJson[+O](
    private val javaTask: io.dapr.durabletask.Task[String],
    private val error: String,
)(using codec: JsonCodec[O])
    extends Task[O]:
  def isDone: Boolean = javaTask.isDone()
  def isCancelled: Boolean = javaTask.isCancelled()
  def await(): O = {
    val result = javaTask.await()
    val json = if result == null then "null" else result
    codec.decode(json) match
      case Right(v)  => v
      case Left(err) => throw RuntimeException(error, err)
  }
  def map[U](f: O => U): Task[U]^{f} = new TaskMap(this, f)

@scala.caps.assumeSafe
private[internal] final class TaskUnit(
    private val javaTask: io.dapr.durabletask.Task[Void],
) extends Task[Unit]:
  def isDone: Boolean = javaTask.isDone()
  def isCancelled: Boolean = javaTask.isCancelled()
  def await(): Unit = javaTask.await()
  def map[U](f: Unit => U): Task[U]^{f} = new TaskMap(this, f)

@scala.caps.assumeSafe
private[internal] final class TaskMap[O1, +O](
    private val task: Task[O1],
    private val g: O1 => O,
) extends Task[O]:
  def isDone: Boolean = task.isDone
  def isCancelled: Boolean = task.isCancelled
  def await(): O = g(task.await())
  // CC can't express that Task[O1] holds a TaskMap^{this.g}; cast this to Task[O] to pass it
  // as the recursive task arg. Return type is still honest: callers see ^{this, f}.
  def map[U](f: O => U): Task[U]^{this, f} = new TaskMap(this.asInstanceOf[Task[O]], f)

/** Wraps `io.dapr.workflows.WorkflowContext` (Java SDK) and exposes the Scala [[WorkflowContext]] trait. */
@scala.caps.assumeSafe
private[internal] final class WorkflowContextImpl(
    private val ctx: io.dapr.workflows.WorkflowContext,
) extends WorkflowContext:

  def instanceId: WorkflowInstanceId =
    WorkflowInstanceId(ctx.getInstanceId.nn)

  def isReplaying: Boolean = ctx.isReplaying

  def getInput[I: JsonCodec]: Option[I] =
    // The input may be stored as a JSON string literal (from DaprWorkflowClient.scheduleNewWorkflow
    // with a Java String input, which Jackson re-serializes as a JSON string) or as a raw JSON
    // value (from the HTTP workflow start API, which stores the body verbatim).
    // Using JsonNode handles both: TextNode.asText() unwraps a JSON string literal;
    // ObjectNode.toString() returns the raw JSON object as a string.
    val node = ctx.getInput(classOf[com.fasterxml.jackson.databind.JsonNode])
    if node == null then None
    else
      val json = if node.isTextual then node.asText() else node.toString
      summon[JsonCodec[I]].decode(json).toOption

  // Return types are annotated ^{this} to match the WorkflowContext trait: a Task captures the
  // context so capture checking forbids it from outliving `run`. The TaskJson/TaskUnit instances
  // are @assumeSafe (empty capture); widening empty -> ^{this} is sound, same as every sub-capability.
  def callActivity[A](using d: ActivityDef[A])(input: d.Input): Task[d.Output]^{this} =
    val inputJson = d.inputCodec.encode(input)
    val name      = d.activityName
    val javaTask  = ctx.callActivity(name, inputJson, classOf[String])
    new TaskJson(javaTask, s"Failed to decode result of activity '$name'")(using d.outputCodec)

  def callActivity[A](using d: ActivityDef[A], ev: d.Input =:= Unit): Task[d.Output]^{this} =
    val name     = d.activityName
    val javaTask = ctx.callActivity(name, "null", classOf[String])
    new TaskJson(javaTask, s"Failed to decode result of activity '$name'")(using d.outputCodec)

  def callActivityByName[I: JsonCodec, O: JsonCodec](name: ActivityName, input: I): Task[O]^{this} =
    val inputJson = summon[JsonCodec[I]].encode(input)
    val javaTask  = ctx.callActivity(name.value, inputJson, classOf[String])
    new TaskJson(javaTask, s"Failed to decode result of activity '${name.value}'")

  def callActivityByName[O: JsonCodec](name: ActivityName): Task[O]^{this} =
    val javaTask = ctx.callActivity(name.value, "null", classOf[String])
    new TaskJson(javaTask, s"Failed to decode result of activity '${name.value}'")

  def createTimer(duration: FiniteDuration): Task[Unit]^{this} =
    val javaTask = ctx.createTimer(java.time.Duration.ofNanos(duration.toNanos))
    new TaskUnit(javaTask)

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: FiniteDuration): Task[T]^{this} =
    val javaTask = ctx.waitForExternalEvent(name.value, java.time.Duration.ofNanos(timeout.toNanos), classOf[String])
    new TaskJson[T](javaTask, s"Failed to decode external event '${name.value}'")

  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T]^{this} =
    val javaTask = ctx.waitForExternalEvent(name.value, classOf[String])
    new TaskJson[T](javaTask, s"Failed to decode external event '${name.value}'")

  def complete[O: JsonCodec](output: O): Unit =
    val json = summon[JsonCodec[O]].encode(output)
    // Pass as JsonNode so that Jackson stores the value as raw JSON, not as a double-encoded
    // JSON string literal (which would happen if we passed a Java String to complete(Object)).
    ctx.complete(Json.mapper.readTree(json))

  // ContinueAsNewInterruption thrown here must reach the runtime — do not catch it.
  def continueAsNew[I: JsonCodec](input: I): Unit =
    ctx.continueAsNew(summon[JsonCodec[I]].encode(input))

  def newUuid(): java.util.UUID =
    ctx.newUuid().nn
