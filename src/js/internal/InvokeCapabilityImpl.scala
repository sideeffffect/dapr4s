//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import JsInterop.*
// The TYPE comes from the deep module (types are erased — no import is emitted), but the VALUES
// must be read off the "@dapr/dapr" root re-export: ScalablyTyped's deep-module specifiers
// (`@dapr/dapr/enum/HttpMethod.enum`) carry no `.js` extension and `@dapr/dapr` has no `exports`
// map, so Node ESM (the Wasm/JSPI production target) cannot resolve them — ERR_MODULE_NOT_FOUND
// at load time (runtime-verified). Only root re-exports may be referenced in value position.
import typings.daprDapr.enumHttpMethodDotenumMod.HttpMethod as SdkHttpMethod
import typings.daprDapr.mod.HttpMethod as SdkHttpMethods
import typings.daprDapr.typesInvokerOptionsDottypeMod.InvokerOptions
import typings.node.globalsMod.global as NodeGlobals
import typings.undiciTypes.fetchMod.RequestInit

@scala.caps.assumeSafe
private object InvokeCapabilityImpl:

  /** Invoke over the raw sidecar HTTP API (`{verb} /v1.0/invoke/{appId}/method/{method}`), bypassing the SDK.
    *
    * Exists because the SDK cannot transmit JS-falsy payloads: `HTTPClient.execute` only attaches a body when
    * `if (params?.body)` is truthy (`node_modules/@dapr/dapr/implementation/Client/HTTPClient/HTTPClient.js`), so the
    * JSON documents `0`, `false`, `null` and `""` would be sent with an '''empty''' body. Same raw-fetch precedent as
    * [[ActorCapabilityImpl]]; the pre-encoded JSON string goes on the wire verbatim with
    * `Content-Type: application/json` (no SDK serializer involved, so no double encoding) and metadata as extra headers
    * like the SDK path. The method name is encoded segment-by-segment: Dapr method names may legitimately be
    * multi-segment routes (`api/orders`), where the `/` must survive as a path separator while everything else is
    * percent-encoded. The response is handled like the SDK path: non-2xx throws (raw-fetch message shape), an empty
    * body reaches the codec as `null` (the JVM's empty `Mono` → `null` bytes), and a non-empty body '''is''' already
    * the JSON document, decoded directly.
    */
  private def rawInvoke[Resp: JsonCodec](
      sidecar: SidecarConfig,
      appId: AppId,
      method: InvokeMethodName,
      json: String,
      httpMethod: HttpMethod,
      metadata: Map[MetadataKey, MetadataValue],
  ): Resp =
    import ActorCapabilityImpl.urlSegment
    val methodPath = method.value.split("/").nn.map(s => urlSegment(s.nn)).mkString("/")
    val base = ActorCapabilityImpl.httpBase(sidecar)
    val url = s"$base/v1.0/invoke/${urlSegment(appId.value)}/method/$methodPath"
    // baseHeaders supplies Content-Type: application/json + dapr-api-token; metadata adds headers
    // on top, mirroring the SDK path's InvokerOptions.headers.
    val headers = ActorCapabilityImpl.baseHeaders(sidecar)
    metadata.foreach { case (k, v) => headers.push(js.Array(k.value, v.value)): Unit }
    // fetch only auto-uppercases the six spec-listed methods (notably NOT "patch"), so uppercase
    // explicitly like the SDK does (`params?.method.toLocaleUpperCase()`).
    val init = RequestInit().setMethod(toJsMethod(httpMethod).toUpperCase).setHeaders(headers).setBody(json)
    val response = JsAwait.await(NodeGlobals.fetch(url, init))
    // Always consume the body (Node fetch keep-alive invariant — see HttpActorContext.postJson).
    val text = JsAwait.await(response.text())
    if response.status >= 400 then throw new RuntimeException(s"Dapr API error ${response.status} at $url: $text")
    JsonCodec.decodeOrThrow[Resp](if text.isEmpty then null else text)

  /** dapr4s [[HttpMethod]] → the SDK's lowercase `HttpMethod` string values (`enum/HttpMethod.enum.js`). The
    * intersection with `String` is how ScalablyTyped types the enum's members (a TS string enum), and it keeps plain
    * string operations (`toUpperCase` in [[rawInvoke]]) available.
    *
    * WHAT (Head/Options branches): `asInstanceOf` conjuring an `SdkHttpMethod` from a string literal.
    *
    * WHY: the SDK enum only declares get/delete/post/put/patch — there are no HEAD/OPTIONS members to reference — but
    * dapr4s supports those verbs.
    *
    * WHY SAFE: the runtime representation of the TS string enum IS the string; `"head"`/`"options"` flow verbatim into
    * `HTTPClient.execute`, which upper-cases the value and hands it to fetch (`clientOptions.method =
    * params?.method.toLocaleUpperCase()`) — verified in the SDK sources, same contract as the five declared members.
    */
  private def toJsMethod(m: HttpMethod): SdkHttpMethod & String =
    m match
      case HttpMethod.Get     => SdkHttpMethods.GET
      case HttpMethod.Post    => SdkHttpMethods.POST
      case HttpMethod.Put     => SdkHttpMethods.PUT
      case HttpMethod.Delete  => SdkHttpMethods.DELETE
      case HttpMethod.Patch   => SdkHttpMethods.PATCH
      case HttpMethod.Head    => "head".asInstanceOf[SdkHttpMethod & String]
      case HttpMethod.Options => "options".asInstanceOf[SdkHttpMethod & String]

  /** WHAT: asInstanceOf viewing a parsed JSON value (`js.Any`) as `js.Object`, the SDK's invoke-data type.
    *
    * WHY: the TypeScript signature (`data?: object`) is narrower than the runtime contract — with the explicit
    * application/json header, `serializeHttp` JSON.stringify-s '''any''' JS value, and dapr4s must pass JSON scalar
    * documents (truthy numbers/booleans/strings) down this path. Only JS-falsy values are excluded (they take the
    * rawInvoke bypass).
    *
    * WHY SAFE: erased, zero-cost view change; every value here came out of `JSON.parse`, and the SDK's only operation
    * on it is the single `JSON.stringify` that puts our original document back on the wire. Same rationale as
    * `PublishCapabilityImpl.asPublishData`.
    */
  private def asInvokeData(parsed: js.Any): js.Object =
    parsed.asInstanceOf[js.Object]

@scala.caps.assumeSafe
private[internal] final class InvokeCapabilityImpl(
    scope: DaprCapabilityImpl,
) extends InvokeCapability:

  import InvokeCapabilityImpl.*

  def invoke[Req: JsonCodec](
      appId: AppId,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Resp =
    val json = summon[JsonCodec[Req]].encode(data)
    val parsed = parseJson(json)
    // Falsy payloads (0, false, null, "") cannot take the SDK path: HTTPClient.execute attaches
    // the request body only behind a JS truthiness check (`if (params?.body)` — implementation/
    // Client/HTTPClient/HTTPClient.js), so the body would be silently dropped. They go through
    // rawInvoke (raw fetch) instead — see JsInterop.isFalsyJson.
    if isFalsyJson(parsed) then rawInvoke[Resp](scope.sidecar, appId, method, json, httpMethod, metadata)
    else
      // Metadata maps to extra HTTP headers (InvokerOptions.headers — the JVM impl's invokeMethod
      // metadata are headers too). The explicit Content-Type: application/json header makes the
      // SDK's serializeHttp JSON.stringify the parsed value rather than inferring text/plain for
      // JSON scalar payloads — same wire-format reasoning as PublishCapabilityImpl.publish.
      val headers = toDict[Any](metadata)
      headers("Content-Type") = "application/json"
      val response = JsAwait.await(
        scope.client.invoker.invoke(
          appId.value,
          method.value,
          toJsMethod(httpMethod),
          asInvokeData(parsed),
          InvokerOptions().setHeaders(headers),
        ),
      )
      // An empty response body surfaces as "" (HTTPClient.execute's tryParseJson); jsonStringOrNull
      // maps it to null so the codec sees the same input as on the JVM (empty Mono → null bytes).
      JsonCodec.decodeOrThrow[Resp](jsonStringOrNull(response))

  def invoke[Resp: JsonCodec](appId: AppId, method: InvokeMethodName): Resp =
    val response = JsAwait.await(scope.client.invoker.invoke(appId.value, method.value, SdkHttpMethods.GET))
    JsonCodec.decodeOrThrow[Resp](jsonStringOrNull(response))
