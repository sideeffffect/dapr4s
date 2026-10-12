//> using target.platform "scala-js"
package dapr4s.internal

import scala.scalajs.js

/** The single place where the JS internal layer bridges `js.Promise` to a synchronous return value.
  *
  * This is the Scala.js analogue of `MonoOps.awaitResult()` on the JVM: every capability implementation funnels its one
  * asynchronous boundary through this object, so the rest of the codebase stays in direct style.
  *
  * ==How it works (Wasm + JSPI)==
  *
  * `js.await` outside a lexically enclosing `js.async` block is an "orphan await", enabled by the
  * `scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait` import below. On the experimental WebAssembly backend, JavaScript
  * Promise Integration (JSPI) '''suspends the entire Wasm stack''' at this point and returns control to the event loop
  * — no thread is blocked (there are none), inbound work keeps being served, and the stack resumes when the promise
  * settles. This is the exact architectural analogue of a virtual thread parking in `CompletableFuture.get()` on the
  * JVM.
  *
  * Requirements this imposes on callers (documented on [[dapr4s.Dapr]]):
  *   - somewhere up the '''Scala''' call stack there must be a dynamically enclosing `js.async { ... }` with no
  *     JavaScript frame in between (otherwise the engine throws `WebAssembly.SuspendError`) — that is why `Dapr.run`
  *     callers enter `js.async` once at the program edge, and why every SDK callback that re-enters dapr4s code opens a
  *     fresh `js.async`;
  *   - the program must be linked with the experimental WebAssembly backend (`jsEmitWasm`, ES modules) and run on a
  *     JSPI-capable engine (Node 25+, or Node 23/24 with `--experimental-wasm-jspi`).
  *
  * ==Plain-JS backend: link-time error by design==
  *
  * Orphan awaits only '''link''' when targeting WebAssembly (see the scaladoc of `JSPI.allowOrphanJSAwait`). On the
  * plain JS backend, code paths reaching this object fail at link time, not at runtime — a deliberate, clean failure
  * mode: the pure parts of dapr4s (models, codecs, derivation) still link on plain JS, and only actually touching a
  * capability requires the Wasm backend.
  *
  * ==Rejected promises==
  *
  * `js.await` rethrows a rejected promise's value; a rejected JS `Error` therefore surfaces in Scala as
  * `js.JavaScriptException(error)` (a `RuntimeException`, matched by `NonFatal`).
  *
  * Every promise that comes from a '''Dapr call''' (SDK method, `fetch` to the sidecar) is awaited with the
  * two-argument overload `await(p, ctx)`, which hands that rejection to [[JsErrors.translate]] — the JS wall's
  * translation point to [[dapr4s.DaprException]], so no `js.JavaScriptException` carrying a sidecar error reaches user
  * code. The one-argument overload is for promises that are not Dapr calls (reading a `fetch` body, the workflow
  * runtime's own start, coroutine resumption). Call sites that need an expected outcome as a value (ETag conflicts,
  * workflow wait timeouts) await raw and inspect the rejection — see `StateCapabilityImpl.softFailure` and
  * `WorkflowCapabilityImpl.waitForCompletion`.
  */
@scala.caps.assumeSafe
private[dapr4s] object JsAwait:
  import scala.scalajs.js.wasm.JSPI.allowOrphanJSAwait

  /** Suspend until `p` settles; return its value or rethrow its rejection as `js.JavaScriptException`. */
  def await[A](p: js.Promise[A]): A = js.await(p)

  /** Suspend until the Dapr call `p` settles; return its value or throw its rejection translated to a
    * [[dapr4s.DaprException]] for the call described by `ctx`.
    *
    * Catches only `NonFatal` (a rejection is always a `js.JavaScriptException`, a `RuntimeException`). Rethrowing needs
    * a `CanThrow` in scope, supplied as `MonoOps.awaitResult` does it (`unsafeExceptions.canThrowAny`): this is the
    * documented propagation point, exception capabilities are not used for Dapr errors.
    */
  def await[A](p: js.Promise[A], ctx: ErrorContext): A =
    given CanThrow[Exception] = unsafeExceptions.canThrowAny
    try js.await(p)
    catch case scala.util.control.NonFatal(e) => throw JsErrors.translate(ctx, e)
