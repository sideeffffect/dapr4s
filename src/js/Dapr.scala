//> using target.platform "scala-js"
package dapr4s

import scala.scalajs.js
import scala.util.control.NonFatal
import dapr4s.internal.facade

/** Entry point that manages the [[DaprCapability]] lifecycle — the Scala.js twin of the JVM `Dapr`, backed by the Dapr
  * JS SDK (`@dapr/dapr`).
  *
  * Construct with a [[DaprConfig]] (defaults to sensible local-sidecar settings) and call `run` (or the JS-only
  * `runAsync`):
  *
  * {{{
  *   // one-shot request/response, with the single js.async entry at the program edge:
  *   def main(args: Array[String]): Unit =
  *     js.async {
  *       Dapr().run:
  *         summon[DaprCapability].state(StateStoreName("statestore")).get(StateStoreKey("k"))
  *     }: Unit
  * }}}
  *
  * ==WebAssembly + JSPI requirement==
  *
  * The capability implementations stay in direct (synchronous-looking) style by suspending on every SDK promise via an
  * orphan `js.await` (see [[dapr4s.internal.JsAwait]]). That mechanism — JavaScript Promise Integration — is the JS
  * analogue of the virtual threads the JVM scaladoc documents: instead of parking a virtual thread in
  * `CompletableFuture.get()`, JSPI suspends the WebAssembly stack and lets the event loop keep running. It comes with
  * hard platform requirements:
  *
  *   - link with the '''experimental WebAssembly backend''' (`//> using jsEmitWasm true`, `//> using jsModuleKind es`);
  *     on the plain JS backend, code reaching `run` '''fails at link time''' — by design, a clean failure mode (the
  *     pure parts of dapr4s still link on plain JS);
  *   - run on '''Node 25+''' (JSPI on by default) or Node 23/24 with `--experimental-wasm-jspi`;
  *   - the caller must be inside a `js.async { ... }` block — one entry at the program edge as in the example above (or
  *     use [[runAsync]], which wraps it for you). Suspension cannot cross a JavaScript stack frame, so a Scala lambda
  *     invoked by a JS API must open its own `js.async` before touching capabilities.
  *
  * ==Configuration mapping==
  *
  * `config.sidecar.httpEndpoint` drives the default HTTP-protocol SDK client; `grpcEndpoint` drives the lazily created
  * gRPC client (configuration + crypto are gRPC-only in the JS SDK) and the workflow client; `apiToken` becomes the
  * SDK's `daprApiToken`. Config knobs without a JS equivalent — the OkHttp pool settings
  * (`httpClientReadTimeout`/`MaxRequests`/`MaxIdleConnections`), the gRPC-Java keepalive settings, `maxRetries`,
  * `timeout`, and the TLS material paths (`grpcTlsCertPath`/`KeyPath`/`CaPath`, `grpcTlsInsecure`) — are ignored on
  * this platform (TLS on/off still follows the endpoint URI scheme).
  *
  * Annotated `@scala.caps.assumeSafe` so that safe-mode user code can call `Dapr(config).run` without seeing any unsafe
  * operations. The internal use of `DaprCapabilityImpl` (a JS-SDK-backed class) and the SDK clients it wraps are
  * managed entirely here.
  */
@scala.caps.assumeSafe
class Dapr(config: DaprConfig = DaprConfig()):

  /** Acquire a Dapr JS SDK client, run `body` with a `DaprCapability` in context, then release the client whether
    * `body` completes normally or throws.
    *
    * Three clients are potentially created: an HTTP-protocol `DaprClient` (always), a gRPC-protocol `DaprClient` and a
    * `DaprWorkflowClient` (lazily, only when `configuration`/`crypto` / `workflow` are first used). All three are
    * stopped in the `finally` block in order; if any stop throws a non-fatal exception, it is suppressed onto the
    * body's throwable (or rethrown standalone if the body succeeded) — the same tryClose+suppression structure as the
    * JVM `Dapr.run` (minus its `InterruptedException` branch: there are no threads to interrupt on JS).
    *
    * No eager `client.start()` is needed: every SDK sub-client call auto-starts its client (awaiting sidecar health) on
    * first use — mirroring the JVM, where `DaprClientBuilder.build()` is also lazy.
    *
    * Must be called within a `js.async { ... }` context on the Wasm backend — see the class scaladoc; use [[runAsync]]
    * when a `js.Promise` is the more natural shape at the call site.
    *
    * @param body
    *   a pure context function that receives a `DaprCapability`
    * @return
    *   the value returned by `body`
    */
  def run[T](body: DaprCapability ?=> T): T =
    val sc = config.sidecar
    val client = new facade.DaprClient(internal.DaprCapabilityImpl.httpClientOptions(sc))
    val grpcClientRef = new internal.LazyClientRef[facade.DaprClient]
    val workflowClientRef = new internal.LazyClientRef[facade.DaprWorkflowClient]
    val impl = new internal.DaprCapabilityImpl(client, sc, grpcClientRef, workflowClientRef)
    var primary: Throwable | Null = null
    try body(using impl)
    catch
      case NonFatal(t) =>
        primary = t
        throw t
    finally
      var closeEx: Throwable | Null = null
      // Awaiting the SDK's async stop() keeps run's contract synchronous: it only returns once all
      // connections are released (the JVM twin's close() calls are synchronous too). The JsAwait
      // suspension rules apply, which is fine — the finally block runs in the same Wasm/JSPI
      // context as run itself.
      def tryClose(stop: () => js.Promise[Unit]): Unit =
        try internal.JsAwait.await(stop())
        catch
          case NonFatal(t) =>
            if closeEx == null then closeEx = t
            else closeEx.nn.addSuppressed(t)
      tryClose(() => client.stop())
      grpcClientRef.created.foreach(c => tryClose(() => c.stop()))
      workflowClientRef.created.foreach(c => tryClose(() => c.stop()))
      val ce = closeEx
      if ce != null then
        val p = primary
        if p != null then p.addSuppressed(ce)
        else throw ce

  /** JS-only convenience: [[run]] wrapped in its own `js.async { ... }` entry, returning the result as a `js.Promise`.
    * Use this when the caller is plain JavaScript-side code (or a `main` that has nothing else to await) and does not
    * want to open the `js.async` block itself.
    */
  def runAsync[T](body: DaprCapability ?=> T): js.Promise[T] =
    js.async {
      run(body)
    }

  /** Start the inbound app channel (pub/sub subscriptions, invocation routes, bindings, actors) described by the
    * [[DaprApp]] returned from `body`.
    *
    * '''Not implemented on Scala.js yet.''' A follow-up phase adds the implementation on top of the JS SDK's
    * `DaprServer` (express-based HTTP app channel).
    */
  // TODO(scala-js serve phase): implement over facade'd DaprServer — pubsub.subscribe/invoker.listen/
  // binding.receive for the SDK-supported routes, raw express routes for the dapr4s app-channel extras
  // (/dapr/config, actors, jobs), every callback re-entering js.async before dispatch.
  def serve(body: DaprCapability ?=> DaprApp): Nothing =
    throw new UnsupportedOperationException("dapr4s serve() on Scala.js is not implemented yet")

  /** JS-only convenience twin of [[serve]], mirroring [[runAsync]]. Like [[serve]], not implemented yet. */
  def serveAsync(body: DaprCapability ?=> DaprApp): js.Promise[Nothing] =
    js.async {
      serve(body)
    }
