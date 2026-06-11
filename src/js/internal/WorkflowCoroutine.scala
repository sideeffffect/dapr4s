//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSName

/** The `{value, done}` object the AsyncGenerator protocol requires every `next()`/`throw()` step to resolve to (the
  * orchestration executor destructures exactly these two properties — `runtime-orchestration-context.js` lines
  * 113/135/162).
  */
private[internal] final class StepResult(val value: js.Any, val done: Boolean) extends js.Object

/** Control-flow signal for [[dapr4s.WorkflowContext.continueAsNew]] on Scala.js — the platform twin of
  * `io.dapr.durabletask.interruption.ContinueAsNewInterruption` on the JVM.
  *
  * The JS SDK's `continueAsNew` only records state on the orchestration context (`setContinuedAsNew`); it does not
  * unwind the workflow body the way the Java SDK's interruption does. dapr4s therefore throws this signal itself so
  * that user code after `continueAsNew` does not run — the same user-visible contract as the JVM. The fiber root in
  * [[WorkflowCoroutine]] catches it (and nothing else) and treats it as normal completion; the SDK then emits only the
  * CONTINUED_AS_NEW action because `setContinuedAsNew` already marked the context complete, making the executor's
  * subsequent `setComplete` a no-op (`runtime-orchestration-context.js` `setComplete` returns early when
  * `_isComplete`).
  *
  * Extends `ControlThrowable` so that a user's broad `catch case NonFatal(e)` cannot swallow it. (On the JVM the
  * interruption is a `RuntimeException` that `NonFatal` *would* match — the documented contract is "never catch it" on
  * both platforms; the JS encoding merely enforces it.)
  */
private[internal] final class ContinueAsNewSignal extends scala.util.control.ControlThrowable

/** Coroutine bridge between a synchronous dapr4s [[dapr4s.Workflow]] body and the async-generator protocol the Dapr JS
  * SDK's orchestration executor drives.
  *
  * ==What the executor actually does (vendored sources, read before touching this file)==
  *
  * `worker/orchestration-executor.js` (EXECUTIONSTARTED case): `const result = await fn(ctx, input)` — the registered
  * function is expected to '''create''' a generator without running its body — then duck-types it via
  * `typeof result?.[Symbol.asyncIterator] === "function"` and hands it to the runtime context.
  * `worker/runtime-orchestration-context.js` then drives it strictly sequentially:
  *   - `run()`: `await generator.next()` once; `{done: true}` completes the orchestration with `value`, otherwise
  *     `value` becomes `_previousTask`.
  *   - `resume()` (called once per history event that completes a task): when `_previousTask.isFailed`,
  *     `await generator.throw(_previousTask._exception)`; when `_previousTask.isComplete`,
  *     `await generator.next(_previousTask._result)` in a loop that keeps feeding results while the newly yielded task
  *     is already complete (this loop is what makes replay work). Every yielded `value` must be
  *     `instanceof durabletask.Task` — so [[exchange]] hands over the '''SDK's own Task instances''', never wrappers.
  *   - `generator.return()` is '''never called''' (verified: no `.return(` call site in the executor or the runtime
  *     context), so [[`return`]] fails loudly instead of pretending to support cancellation semantics.
  *   - After the generator finishes, later events may still trigger `resume()` (the context never clears
  *     `_previousTask`), so a finished generator must answer post-completion `next()` with `{done: true}` — standard
  *     AsyncGenerator protocol, implemented in [[next]].
  *
  * ==The fiber handshake==
  *
  * The workflow body runs inside its own `js.async { ... }` fiber (Wasm + JSPI). Two pairs of promise resolvers form
  * the handshake:
  *   - ''step'' (`stepResolve`/`stepReject`): settles the promise the executor is currently `await`ing from
  *     `next()`/`throw()`. Resolved with `{value: sdkTask, done: false}` when the fiber yields ([[exchange]]), with
  *     `{value: output, done: true}` when the fiber returns, and rejected when the fiber throws (the executor then
  *     fails the orchestration via its `processEvent`/`execute` catch blocks → `ctx.setFailed`).
  *   - ''resume'' (`fiberResolve`/`fiberReject`): settles the promise the suspended fiber is orphan-`js.await`ing
  *     inside [[exchange]]. Resolved by `next(v)` with the task result, rejected by `throw(e)` with the task failure
  *     (which `js.await` rethrows into the workflow body as `js.JavaScriptException(e)` — the JS counterpart of the
  *     JVM's `TaskFailedException` from `Task.await()`).
  *
  * ==Why the unsynchronized handshake is safe (the load-bearing invariant)==
  *
  * The executor `await`s every `next()`/`throw()` before processing the next history event, so the generator side and
  * the fiber strictly alternate — at any instant at most one of the two is runnable: between `next(v)` and the
  * resulting yield/completion only the fiber runs (the executor is suspended on the step promise); between a yield and
  * the following `next(v)` only the executor runs (the fiber is suspended on the resume promise). Combined with
  * JavaScript's single-threaded execution (JSPI resumes a suspended Wasm stack as a promise reaction, never
  * concurrently), each resolver field is written in one phase and consumed-and-cleared in the other, so the plain
  * `var`s need no synchronization and the `IllegalStateException` branches below are genuinely unreachable under the
  * vendored executor — they exist to fail loudly if a future SDK version ever drives the generator concurrently.
  *
  * ==Replay and the abandoned fiber==
  *
  * Each work item re-executes the orchestrator from scratch: a fresh generator (and so a fresh fiber) replays the full
  * history. When history runs out at an incomplete task, the executor simply stops driving the generator and returns
  * its accumulated actions; the fiber stays suspended on a resume promise that nobody will resolve and the whole
  * coroutine graph becomes garbage once the executor drops it — abandoned JSPI stacks are collectable by design. This
  * is the JS analogue of the JVM's `OrchestratorBlockedException`, which unwinds the orchestrator thread instead. One
  * user-visible consequence of the differing mechanisms: a `try`/`finally` around a never-completing `Task.await()`
  * runs its finalizer on every replay on the JVM (the unwind passes through it) but not on JS (the fiber is abandoned
  * mid-suspension). Workflow code must be deterministic and effect-free outside activities anyway, so a finalizer with
  * observable effects is already outside the contract on both platforms.
  *
  * ==Escape hatches==
  *
  * WHAT: the class is `@scala.caps.assumeSafe` and extends `js.Object` (a non-native JS class, so `next`/`throw`/
  * `return` and the `Symbol.asyncIterator` member are real JS properties the executor can call).
  *
  * WHY: capture checking cannot see through the promise handshake — the fiber closure captures `this` and the
  * `Workflow`, and resolver functions flow through `js.Promise` constructors (JS interop types carry no capture
  * annotations).
  *
  * WHY SAFE: every captured value lives exactly as long as the orchestration execution that owns it: the coroutine, its
  * context, and the resolvers are all dropped together when the executor finishes the work item, and the alternation
  * invariant above guarantees no value is used from two phases at once. See `DaprAppServer.erased` for the canonical
  * JS-interop capture-erasure rationale.
  */
@scala.caps.assumeSafe
private[internal] final class WorkflowCoroutine(
    private val workflow: Workflow,
    private val sdkCtx: facade.SdkWorkflowContext,
    private val input: js.Any,
) extends js.Object:

  // -------------------------------------------------------------------------
  // Handshake state. Single-threaded; see the alternation invariant in the
  // class doc for why plain unsynchronized `var`s are correct.
  // -------------------------------------------------------------------------

  /** The fiber has been started (first `next()` seen). */
  private var started: Boolean = false

  /** The fiber has returned or thrown; the generator answers `{done: true}` from now on. */
  private var finished: Boolean = false

  /** The workflow output recorded by `WorkflowContext.complete` (parsed JS value, `undefined` when never called).
    * Becomes the generator's final `{done: true, value}` — which the executor stores via `setComplete` and
    * `JSON.stringify`s once onto the wire, the same raw-JSON output convention as the JVM impl's
    * `ctx.complete(JsonNode)`.
    */
  private var output: js.Any = js.undefined

  /** Settles the executor's currently pending `next()`/`throw()` promise. Non-null exactly while the executor is
    * suspended on a step.
    */
  private var stepResolve: js.Function1[StepResult, Unit] | Null = null
  private var stepReject: js.Function1[scala.Any, Unit] | Null = null

  /** Settles the resume promise the fiber is suspended on inside [[exchange]]. Non-null exactly while the fiber is
    * suspended on a yield.
    */
  private var fiberResolve: js.Function1[js.Any, Unit] | Null = null
  private var fiberReject: js.Function1[scala.Any, Unit] | Null = null

  // -------------------------------------------------------------------------
  // Fiber side (called from the workflow body, inside the js.async fiber)
  // -------------------------------------------------------------------------

  /** `Task.await()`: hand `task` (one of the SDK's own Task instances — the executor `instanceof`-checks it) to the
    * pending generator step, suspend this fiber on a fresh resume promise, and return whatever value the executor's
    * next `next(v)` delivers — or rethrow (as `js.JavaScriptException`) whatever `throw(e)` injects.
    *
    * The resume promise is registered '''before''' the step is answered so that the executor's follow-up `next(v)`
    * (even a hypothetical synchronous one) always finds the resolver in place.
    */
  final private[internal] def exchange(task: facade.SdkTask): js.Any =
    val resume = new js.Promise[js.Any]((resolve, reject) => {
      fiberResolve = (v: js.Any) => { resolve(v); () }
      fiberReject = (e: scala.Any) => { reject(e); () }
      ()
    })
    answerStep(new StepResult(task, false))
    JsAwait.await(resume)

  /** Record the workflow output (`WorkflowContext.complete`); surfaced as the generator's completion value. */
  final private[internal] def recordOutput(v: js.Any): Unit =
    output = v

  // -------------------------------------------------------------------------
  // Generator side (called by the orchestration executor, from JS frames)
  // -------------------------------------------------------------------------

  /** AsyncGenerator `next`. The first call starts the fiber (mirroring real generator semantics — the executor's
    * comment at the `await fn(ctx, input)` call site relies on creation not executing the body); later calls resume the
    * suspended fiber with `value`. Resolves with the fiber's next yield or its completion; called after completion,
    * resolves `{value: undefined, done: true}` per the AsyncGenerator protocol (the executor does this when an event
    * arrives for an already-finished orchestration — see the class doc).
    */
  def next(value: js.Any = js.undefined): js.Promise[StepResult] =
    if finished then js.Promise.resolve[StepResult](new StepResult(js.undefined, true))
    else
      step { () =>
        if !started then
          started = true
          startFiber()
        else resumeFiber(value)
      }

  /** AsyncGenerator `throw` — how the executor delivers a failed task (`resume()` calls
    * `generator.throw(task._exception)` with the `TaskFailedError`). Rejects the fiber's resume promise so the pending
    * `Task.await()` rethrows it inside the workflow body; the body may catch it (the step then resolves with the next
    * yield) or let it escape (the step rejects and the executor fails the orchestration). On a finished generator it
    * rejects with `error`, matching the protocol.
    */
  def `throw`(error: js.Any = js.undefined): js.Promise[StepResult] =
    if finished then js.Promise.reject(error)
    else if !started then
      // The executor only throws into a generator that already yielded a (failed) task, so the fiber is necessarily
      // started and suspended; anything else is an unsupported driving pattern — fail loudly, do not fake it.
      js.Promise.reject(
        new js.Error(
          "dapr4s WorkflowCoroutine: generator.throw() before the first next() is not supported " +
            "(the durabletask executor never does this — runtime-orchestration-context.js resume())",
        ),
      )
    else step(() => failFiber(error))

  /** AsyncGenerator `return` — '''unsupported''', loudly: the vendored executor never calls it (no `.return(` call site
    * in `orchestration-executor.js` or `runtime-orchestration-context.js`), and pretending to support cancellation
    * would silently skip the workflow body's remaining code without the runtime semantics to back it.
    */
  def `return`(value: js.Any = js.undefined): js.Promise[StepResult] =
    js.Promise.reject(
      new js.Error(
        "dapr4s WorkflowCoroutine: generator.return() is not supported — the durabletask orchestration " +
          "executor drives only next() and throw(); a call here means the driving contract changed upstream",
      ),
    )

  /** The duck-typing hook: `orchestration-executor.js` detects a generator via
    * `typeof result?.[Symbol.asyncIterator] === "function"` (and never actually invokes it). Returning `this` also
    * satisfies the real AsyncIterable protocol for good measure.
    */
  @JSName(js.Symbol.asyncIterator)
  def asyncIterator(): WorkflowCoroutine = this

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  /** Register the step resolvers for one `next()`/`throw()` call, then run `drive` (start/resume/fail the fiber). A
    * `drive` that throws (the unreachable-by-invariant branches) rejects the returned promise — the executor then fails
    * the orchestration with the `IllegalStateException`, which is the loud failure we want.
    */
  private def step(drive: () => Unit): js.Promise[StepResult] =
    new js.Promise[StepResult]((resolve, reject) => {
      if stepResolve != null || stepReject != null then
        throw new IllegalStateException(
          "dapr4s WorkflowCoroutine: a generator step is already pending — the durabletask executor is expected " +
            "to await every next()/throw() before issuing the next one (see the sequential-driving invariant)",
        )
      stepResolve = (r: StepResult) => { resolve(r); () }
      stepReject = (e: scala.Any) => { reject(e); () }
      drive()
      ()
    })

  /** Start the workflow body in its own `js.async` fiber. The body runs synchronously up to its first suspension (first
    * `Task.await()`), which already answers the pending first step; completion/failure are routed to whichever step is
    * pending at that time via promise reactions.
    */
  private def startFiber(): Unit =
    val completion: js.Promise[Unit] = js.async {
      // ContinueAsNewSignal is dapr4s's own control-flow signal (see its doc): continueAsNew was already recorded on
      // the SDK context, so the fiber just stops here — the executor's final setComplete is a no-op. This is a typed
      // catch of our private signal, not a broad catch; everything else must escape and fail the orchestration.
      try workflow.run(using new WorkflowContextImpl(sdkCtx, this, input))
      catch case _: ContinueAsNewSignal => ()
    }
    // Route the fiber's terminal state to the pending step. The handlers never throw (answerStep/failStep fall back
    // to console.error on invariant breach), so the derived promise cannot become an unhandled rejection.
    val onDone: js.Function1[Unit, Unit] = _ => {
      finished = true
      answerStep(new StepResult(output, true))
    }
    // A Scala exception escaping js.async rejects the promise with the Throwable itself (Scala.js Throwables are JS
    // Errors), and js.JavaScriptException unwraps to the underlying JS error — so the executor's newFailureDetails
    // sees the original TaskFailedError/Error, exactly like a native async generator's rejection.
    val onFail: js.Function1[scala.Any, Unit] = err => {
      finished = true
      failStep(err)
    }
    completion.`then`[Unit](onDone, onFail): Unit

  /** Resolve the pending step with a yield or completion result. Called from the fiber ([[exchange]]) or from the
    * completion reactions in [[startFiber]]; by the alternation invariant a step is always pending here.
    */
  private def answerStep(result: StepResult): Unit =
    val resolve = stepResolve
    stepResolve = null
    stepReject = null
    resolve match
      case null =>
        if result.done then
          // Reachable only on invariant breach; nothing can receive the answer, so report loudly instead of
          // throwing inside a promise reaction (which would surface as an unhandled rejection and kill Node).
          js.Dynamic.global.console
            .error(
              "dapr4s WorkflowCoroutine: fiber completed with no pending generator step — invariant violated",
            ): Unit
        else
          // From exchange(): throwing here propagates into the workflow body, fails the fiber, and ultimately fails
          // the orchestration — the loudest available failure for a yield nobody is waiting for.
          throw new IllegalStateException(
            "dapr4s WorkflowCoroutine: Task.await() outside a pending generator step — the workflow body may only " +
              "run between the executor's next()/throw() calls (sequential-driving invariant violated)",
          )
      case r => r(result)

  /** Reject the pending step (fiber threw). Same invariant as [[answerStep]]; called only from a promise reaction, so
    * the breach fallback logs instead of throwing.
    */
  private def failStep(error: scala.Any): Unit =
    val reject = stepReject
    stepResolve = null
    stepReject = null
    reject match
      case null =>
        js.Dynamic.global.console
          .error(
            s"dapr4s WorkflowCoroutine: fiber failed with no pending generator step — invariant violated: $error",
          ): Unit
      case r => r(error)

  /** Resume the suspended fiber with a task result (`next(v)`). */
  private def resumeFiber(value: js.Any): Unit =
    val resolve = fiberResolve
    fiberResolve = null
    fiberReject = null
    resolve match
      case null =>
        throw new IllegalStateException(
          "dapr4s WorkflowCoroutine: next(value) with no suspended fiber — the executor resumed a generator " +
            "that never yielded (sequential-driving invariant violated)",
        )
      case r => r(value)

  /** Reject the suspended fiber's resume promise with a task failure (`throw(e)`). */
  private def failFiber(error: scala.Any): Unit =
    val reject = fiberReject
    fiberResolve = null
    fiberReject = null
    reject match
      case null =>
        throw new IllegalStateException(
          "dapr4s WorkflowCoroutine: throw(error) with no suspended fiber — the executor threw into a generator " +
            "that never yielded (sequential-driving invariant violated)",
        )
      case r => r(error)
