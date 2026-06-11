//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import typings.daprDapr.workflowInternalDurabletaskTaskTaskMod.Task as SdkTask
import typings.daprDapr.workflowRuntimeWorkflowContextMod.WorkflowContext as SdkWorkflowContext
import typings.node.cryptoMod

/** dapr4s [[dapr4s.Task]] over an SDK task + a pure decode step — the Scala.js twin of the JVM `TaskJson`/`TaskUnit`
  * pair, unified because on JS every decode starts from the same `js.Any` the coroutine handshake delivers.
  *
  * The SDK task is held with a wildcard element type (`SdkTask[?]`): the orchestration executor only cares about the
  * task '''object''' (its `instanceof Task` check and completion bookkeeping), while its element type varies by
  * producer (`Task[Any]` from `callActivity`/`createTimer`, the composite `WhenAnyTask <: Task[Task[Any]]` from
  * `whenAny`) — the decode step, not the task's type parameter, is what types the result.
  *
  * [[await]] hands the underlying SDK task to the orchestration executor through [[WorkflowCoroutine.exchange]] and
  * decodes whatever value the executor feeds back. During replay the executor feeds already-completed results
  * immediately (its `resume()` fast-loop), so `await()` returns without scheduling new work — the same replay-safety
  * contract as the JVM.
  *
  * Platform notes versus the JVM `Task`:
  *   - a failed task surfaces from `await()` as `js.JavaScriptException(TaskFailedError)` (the JS SDK's failure type),
  *     where the JVM throws `io.dapr.durabletask.TaskFailedException` — both are `RuntimeException`s matched by
  *     `NonFatal`, but the concrete type is necessarily platform-specific (the Java SDK types do not exist on JS);
  *   - [[isCancelled]] is always `false`: the vendored JS task model has no cancellation state (no `isCancelled`
  *     member exists on the SDK `Task`), whereas the JVM SDK cancels e.g. timed-out external-event tasks.
  */
@scala.caps.assumeSafe
private[internal] final class TaskImpl[+O](
    private val sdkTask: SdkTask[?],
    private val coroutine: WorkflowCoroutine,
    // WHAT: the decode step stored capture-erased as AnyRef and cast back in await().
    // WHY: its honest type mentions the boxed type parameter O, so CC manufactures a fresh reach
    // capability at every instantiation ("hiding {}") — a typed field would give each TaskImpl a
    // non-empty capture set, breaking the empty-capture widening to Task[...]^{this} that the
    // WorkflowContext methods rely on (same constraint the JVM TaskMap.map documents).
    // WHY SAFE: the only writer is WorkflowContextImpl.mkTask, which takes a `js.Any => O` of the
    // matching O, and the cast back in await() restores exactly that type; the function itself
    // only closes over codecs/SDK task handles that live as long as the orchestration execution.
    // WHERE TO LOOK: WorkflowActivityBridge.daprRef (JVM) — the canonical AnyRef-erasure pattern.
    private val decodeRef: AnyRef,
) extends Task[O]:
  def isDone: Boolean = sdkTask.isComplete
  def isCancelled: Boolean = false
  def await(): O = decodeRef.asInstanceOf[js.Any => O](coroutine.exchange(sdkTask))
  def map[U](f: O => U): Task[U]^{f} = new TaskMap(this, f)

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

/** Implements the dapr4s [[dapr4s.WorkflowContext]] over the SDK's public workflow context + the
  * [[WorkflowCoroutine]] handshake — the Scala.js twin of the JVM `WorkflowContextImpl`, same contract method for
  * method. (The SDK context/task types are the ScalablyTyped-generated ones, imported under `Sdk*` aliases because
  * their natural names collide with the dapr4s public types `WorkflowContext`/`Task` that this very file must also
  * reference.)
  *
  * ==Wire format (kept byte-identical to the JVM)==
  *
  * The JVM passes codec-encoded JSON '''strings''' through the Java SDK, whose Jackson serializer encodes them once
  * more — so activity inputs/outputs and event payloads travel as JSON string literals wrapping the dapr4s document.
  * The JS executor applies exactly one `JSON.stringify` on the way out (`runtime-orchestration-context.js`
  * `callActivity`, `activity-executor.js` output) and one `JSON.parse` on the way in, so passing the codec-encoded
  * string as the value reproduces the JVM wire format and round-trips through [[jsonOf]] on the receiving side.
  * Passing the '''string''' (always truthy when non-empty, and codec output is never empty) also sidesteps the SDK's
  * `input ? JSON.stringify(input) : undefined` falsy-input bug, which would silently drop inputs like `0` or `false`
  * if parsed values were handed over. Workflow '''outputs''' are the exception, exactly as on the JVM (which passes a
  * `JsonNode` to `complete` for the same reason): the parsed value is recorded so the executor's single
  * `JSON.stringify` puts raw JSON on the wire.
  */
@scala.caps.assumeSafe
private[internal] final class WorkflowContextImpl(
    private val ctx: SdkWorkflowContext,
    private val coroutine: WorkflowCoroutine,
    private val input: js.Any,
) extends WorkflowContext:

  import WorkflowContextImpl.*

  /** Per-execution `newUuid` counter — replay-deterministic because each replay re-runs the (deterministic) body from
    * scratch with a fresh context, mirroring the per-executor counter in the Java SDK (`TaskOrchestrationExecutor`).
    */
  private var uuidCounter: Int = 0

  def instanceId: WorkflowInstanceId =
    WorkflowInstanceId(ctx.getWorkflowInstanceId())

  def isReplaying: Boolean = ctx.isReplaying()

  def getInput[I: JsonCodec]: Option[I] =
    // The executor JSON.parses the raw input before calling the workflow fn (undefined when absent). Like the JVM's
    // JsonNode handling, both client conventions are accepted: a JSON string literal (dapr4s clients and the Java
    // SDK double-encode, see the class doc) decodes its content; any other parsed value (the HTTP workflow start
    // API stores the body verbatim) is re-stringified and decoded as raw JSON.
    if js.isUndefined(input) then None
    else summon[JsonCodec[I]].decode(jsonOf(input)).toOption

  // Return types are annotated ^{this} to match the WorkflowContext trait: a Task captures the
  // context so capture checking forbids it from outliving `run`. The TaskImpl instances are
  // @assumeSafe (empty capture); widening empty -> ^{this} is sound, same as every sub-capability.
  def callActivity[A](using d: ActivityDef[A])(input: d.Input): Task[d.Output]^{this} =
    scheduleActivity(d.activityName, d.inputCodec.encode(input))(using d.outputCodec)

  def callActivity[A](using d: ActivityDef[A], ev: d.Input =:= Unit): Task[d.Output]^{this} =
    scheduleActivity(d.activityName, "null")(using d.outputCodec)

  def callActivityByName[I: JsonCodec, O: JsonCodec](name: ActivityName, input: I): Task[O]^{this} =
    scheduleActivity(name.value, summon[JsonCodec[I]].encode(input))

  def callActivityByName[O: JsonCodec](name: ActivityName): Task[O]^{this} =
    scheduleActivity(name.value, "null")

  private def scheduleActivity[O: JsonCodec](name: String, inputJson: String): TaskImpl[O] =
    val sdkTask = ctx.callActivity(name, inputJson)
    mkTask(sdkTask, decodeJson(summon[JsonCodec[O]], s"Failed to decode result of activity '$name'"))

  /** Construct a [[TaskImpl]], erasing the decode step's capture set into the AnyRef slot — see the `decodeRef`
    * comment on [[TaskImpl]] for the WHAT/WHY/WHY-SAFE of the erasure.
    */
  private def mkTask[O](sdkTask: SdkTask[?], decode: js.Any => O): TaskImpl[O] =
    new TaskImpl(sdkTask, coroutine, decode.asInstanceOf[AnyRef])

  def createTimer(duration: FiniteDuration): Task[Unit]^{this} =
    // The SDK's numeric createTimer overload takes SECONDS (runtime-orchestration-context.js: fireAt * 1000 added to
    // the deterministic current time); fractional seconds carry sub-second precision through the Date arithmetic.
    val sdkTask = ctx.createTimer(seconds(duration))
    mkTask(sdkTask, _ => ())

  def waitForExternalEvent[T: JsonCodec](name: EventName, timeout: FiniteDuration): Task[T]^{this} =
    // The JS SDK has no timeout overload, so the JVM SDK's internal mechanism is reproduced explicitly: race the
    // event against a durable timer (the Java SDK schedules the same internal timer) and fail the await when the
    // timer wins. whenAny's result is the first-completed child Task object (when-any-task.js), compared by
    // reference below. History ordering decides ties exactly like the JVM (the Java SDK's timer callback only
    // cancels when the event task hasn't completed yet). One forced divergence: the JVM throws the Java SDK's
    // io.dapr.durabletask.TaskCanceledException (and marks the task cancelled), which does not exist on JS — the
    // timeout surfaces as java.util.concurrent.TimeoutException instead (the same type the workflow *client*'s
    // waitForCompletion uses for timeouts on both platforms).
    val eventTask = ctx.waitForExternalEvent(name.value)
    val timerTask = ctx.createTimer(seconds(timeout))
    val anyTask = ctx.whenAny(js.Array(eventTask, timerTask))
    val decodeEvent = decodeJson(summon[JsonCodec[T]], s"Failed to decode external event '${name.value}'")
    val eventName = name.value
    mkTask[T](
      anyTask,
      winner =>
        if winner eq timerTask then
          throw new java.util.concurrent.TimeoutException(
            s"external event '$eventName' was not raised within $timeout",
          )
        else decodeEvent(JsInterop.asJsAny(eventTask.getResult())),
    )

  def waitForExternalEvent[T: JsonCodec](name: EventName): Task[T]^{this} =
    val sdkTask = ctx.waitForExternalEvent(name.value)
    mkTask(sdkTask, decodeJson(summon[JsonCodec[T]], s"Failed to decode external event '${name.value}'"))

  def complete[O: JsonCodec](output: O): Unit =
    // Recorded on the coroutine and surfaced as the generator's {done: true, value} when run() returns; the executor
    // then stores it via setComplete and stringifies once — raw JSON on the wire, like the JVM's complete(JsonNode).
    // (Unlike the JVM SDK, completion is not committed until run() returns: scheduling further work after complete()
    // is not flagged with the JVM's "orchestrator already complete" error, it is merely pointless.)
    coroutine.recordOutput(js.JSON.parse(summon[JsonCodec[O]].encode(output)))

  def continueAsNew[I: JsonCodec](input: I): Unit =
    // Encoded-string input for the same wire-format parity as activity inputs (the executor stringifies _newInput
    // once — getActions in runtime-orchestration-context.js). saveEvents = true mirrors the JVM's single-argument
    // continueAsNew, which delegates to continueAsNew(input, preserveUnprocessedEvents = true).
    ctx.continueAsNew(summon[JsonCodec[I]].encode(input), saveEvents = true)
    // The ContinueAsNewSignal thrown here must reach the fiber root in WorkflowCoroutine — do not catch it.
    // (The JS SDK's continueAsNew only records state; the unwind that the Java SDK's ContinueAsNewInterruption
    // provides is added here so code after continueAsNew never runs, the same contract as the JVM.)
    throw new ContinueAsNewSignal

  def newUuid(): java.util.UUID =
    // The JS SDK exposes no deterministic UUID, so the Java SDK's algorithm is mirrored
    // (TaskOrchestrationExecutor.newUuid: RFC 4122 §4.3 name-based v5/SHA-1 over
    // "<instanceId>-<currentUtcDateTime>-<counter>" in a fixed namespace). Determinism argument: instanceId is
    // constant, currentUtcDateTime advances only via ORCHESTRATORSTARTED history timestamps (replayed identically),
    // and the counter restarts at 0 for every (deterministic) re-execution — so the n-th newUuid() of an execution
    // yields the same value on every replay. Cross-platform UUID equality with the JVM is NOT a goal (an instance
    // always replays on the platform that hosts it); only replay-stability is, hence hashing the namespace UUID's
    // string form instead of its raw bytes is fine.
    val name = s"${ctx.getWorkflowInstanceId()}-${ctx.getCurrentUtcDateTime().toISOString()}-$uuidCounter"
    uuidCounter += 1
    deterministicUuidV5(name)

@scala.caps.assumeSafe
private[internal] object WorkflowContextImpl:

  /** FiniteDuration → the fractional seconds the SDK's numeric `createTimer` overload expects. */
  private def seconds(duration: FiniteDuration): Double =
    duration.toMillis.toDouble / 1000.0

  /** Recover the dapr4s JSON document from a value the executor `JSON.parse`d off the wire: a string '''is''' the
    * document (the double-encoded convention shared with the JVM — see the [[WorkflowContextImpl]] doc), an absent
    * value (`undefined` for empty activity results, or `null`) maps to `"null"` exactly like the JVM's
    * `getInput(String.class)` null-handling, and any other parsed value (a foreign, single-encoded producer — e.g. an
    * event raised via the raw HTTP API) is re-stringified. The JVM is stricter on that last case (Jackson fails to
    * read a JSON object as `String`); accepting it here is a harmless superset.
    */
  private[internal] def jsonOf(value: js.Any): String =
    if js.isUndefined(value) || (value: Any) == null then "null"
    else
      (value: Any) match
        case s: String => s
        case _ => js.JSON.stringify(value)

  /** A pure decode step for [[TaskImpl]]: wire value → [[jsonOf]] → codec, failing like the JVM `TaskJson.await`. */
  private def decodeJson[O](codec: JsonCodec[O], error: String): js.Any => O =
    raw =>
      codec.decode(jsonOf(raw)) match
        case Right(v) => v
        case Left(err) => throw RuntimeException(error, err)

  /** Fixed v5 namespace — the same one the Java SDK uses (`TaskOrchestrationExecutor.newUuid`). */
  private val UuidNamespace = "9e952958-5e33-4daf-827f-2fa12937b875"

  /** RFC 4122 §4.3 name-based UUID: SHA-1 over namespace + name (via Node's `crypto`, typed by the ScalablyTyped
    * `@types/node` conversion, since the Scala.js javalib has no `MessageDigest`), truncated to 128 bits with the
    * version (5) and variant bits patched in — the same bit surgery as the Java SDK's `UuidGenerator.generate`.
    */
  private def deterministicUuidV5(name: String): java.util.UUID =
    val hex = cryptoMod
      .createHash("sha1")
      .update(s"$UuidNamespace-$name", cryptoMod.Encoding.utf8)
      .digest(cryptoMod.BinaryToTextEncoding.hex)
    val msb = (java.lang.Long.parseUnsignedLong(hex.substring(0, 16), 16) & 0xffffffffffff0fffL) | (5L << 12)
    val lsb = (java.lang.Long.parseUnsignedLong(hex.substring(16, 32), 16) & 0x3fffffffffffffffL) |
      java.lang.Long.MIN_VALUE // the RFC variant bit pattern 10xx…: 0x8000000000000000
    java.util.UUID(msb, lsb)
