package dapr4s.internal

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wit
import scala.util.control.NonFatal

import dapr4s.*
import dapr4s.invoke.*
import dapr4s.wasm.ComponentRoot
import dapr4swit.dapr_wasm_components.interfaces.{invocation, state as witState, state_read}
import dapr4swit.exports.dapr_wasm_components.interfaces.invocation_callback

/** Inbound side of the `wasm` wall: dispatches WIT `invocation-callback.on-invoke` over the `invokeRoutes` of the app's
  * `DaprApp`. Phase-0 scope: the plain-route form only; a request-envelope route (`InvokeRoute.withRequest`) answers
  * 501.
  *
  * WHY @assumeSafe: this class is the `wasm` platform's counterpart of the JVM/JS `DaprAppServer` — the boundary where
  * WIT-generated types (plain Scala 2.13 classes CC knows nothing about) meet the app's capturing handlers. It holds
  * only the immutable `DaprApp` and never lets a handler escape: each handler is called and its result encoded inside
  * `onInvoke`. Same contract as src/js/internal/DaprAppServer.scala.
  */
@scala.caps.assumeSafe
final class InvokeDispatcher(app: DaprApp) extends witglue.InvocationCallbackExports:
  private val routes: Map[String, InvokeRoute] = app.invokeRoutes.map(r => r.methodName.value -> r).toMap

  private def respond(status: Int, body: String): invocation.HttpResponse =
    invocation.HttpResponse(status.toShort, Array(wit.Tuple2("content-type", "application/json")), body.getBytes(UTF_8))

  private def jsonString(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  override def onInvoke(request: invocation_callback.InvokeRequest): invocation.HttpResponse =
    routes.get(request.method) match
      case None                                     => respond(404, jsonString(s"no route ${request.method}"))
      case Some(route) if route.usesRequestEnvelope =>
        respond(501, jsonString("request-envelope routes are not supported by the wasm prototype"))
      case Some(route) =>
        // WHAT: casting the type-erased `rawHandler` back to `Req => Resp`.
        // WHY: InvokeRoute stores the handler as AnyRef (CC cannot track a capturing function in a
        //   path-dependent field, see Subscription.rawHandler); the JVM/JS servers do the same cast.
        // WHY SAFE: `InvokeRoute.apply` (the only constructor with usesRequestEnvelope = false) stores
        //   exactly a `Q => R` with `Req = Q`, `Resp = R`, and the codecs next to it are for the same types.
        // WHERE: src/js/internal/DaprAppServer.scala (service-invocation routes), src/shared/invoke/InvokeRoute.scala.
        val handler = route.rawHandler.asInstanceOf[route.Req => route.Resp]
        // An empty body decodes as JSON `null`, exactly like the JVM/JS servers.
        val body = if request.data.isEmpty then "null" else new String(request.data, UTF_8)
        // NonFatal only (AGENTS.md): a handler exception becomes a 500, like DaprAppServer; fatal
        // errors propagate and trap the component instance.
        try
          route.reqCodec.decode(body) match
            case Left(err)  => respond(400, jsonString(err.getMessage))
            case Right(req) => respond(200, route.respCodec.encode(handler(req)))
        catch case NonFatal(e) => respond(500, jsonString(s"${e.getClass.getName}: ${e.getMessage}"))

/** Builds the app once per component instance. `wasmtime serve` instantiates per request, so in practice this runs on
  * every request; it is cheap (no I/O).
  *
  * WHY @assumeSafe: it creates the wall's root capability (`WasmStateCapability`) out of nothing, like `Dapr(...)` does
  * on the JVM/JS, and hands it to the app's `DaprComponent.app` as a `using` parameter. The capability's authority is
  * the component's WIT imports, whose lifetime is the component instance's — exactly the lifetime of this object.
  */
@scala.caps.assumeSafe
object WasmRuntime:
  lazy val invocation: witglue.InvocationCallbackExports =
    // The live objects of the hand-written import traits (src/wasm/glue): the only place the wall touches the real WIT imports.
    given state: dapr4s.state.StateCapability = WasmStateCapability(witglue.StateReadImports, witglue.StateImports)
    InvokeDispatcher(ComponentRoot.component.app.validateOrThrow())
