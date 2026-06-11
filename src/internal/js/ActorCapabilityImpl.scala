//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js

/** Client-side capability for invoking methods on a specific Dapr virtual actor instance — the Scala.js twin of the JVM
  * `ActorCapabilityImpl`.
  *
  * ==Why raw sidecar HTTP instead of the JS SDK==
  *
  * The SDK's low-level actor client (`ActorClientHTTP` in `actors/client/ActorClient/`) is exactly what we need, but it
  * is '''not exported from the package root''' (`index.ts` only exports `ActorId`, `ActorProxyBuilder`,
  * `AbstractActor`), and `@dapr/dapr` has no `exports` map, so deep-requiring it is an unsupported API that can break
  * on any release. The exported alternative, `ActorProxyBuilder`, derives the actor type string from
  * `actorTypeClass.name` and returns a JS `Proxy` that turns '''every property access''' into an actor invocation — JS
  * class-name reflection that is hostile to Scala.js (class names are mangled/minified, and the Proxy contract doesn't
  * fit a typed facade). So this class speaks the sidecar's actor HTTP API directly over the Node-global `fetch` +
  * [[JsAwait]] — the same SDK-bypass precedent as the JVM `HttpActorContext` (which bypasses the Java SDK for actor
  * state/reminders/timers for analogous reasons). The verb and path mirror `ActorClientHTTP.invoke`:
  * `POST {sidecar}/v1.0/actors/{type}/{id}/method/{name}` (Dapr accepts PUT and POST; the JS SDK always POSTs).
  *
  * Serialization uses raw pass-through like the JVM twin: the request value is encoded to JSON by our [[JsonCodec]] and
  * sent verbatim as the body with `application/json`; the response body text is decoded by the same codec — no SDK
  * serializer ever touches the payload.
  */
@scala.caps.assumeSafe
private[internal] final class ActorCapabilityImpl(
    val actorType: ActorType,
    val actorId: ActorId,
    private val sidecar: SidecarConfig,
) extends ActorCapability:

  import ActorCapabilityImpl.*

  private def methodUrl(method: ActorMethodName): String =
    val base = httpBase(sidecar)
    val tpe = urlSegment(actorType.value)
    val id = urlSegment(actorId.value)
    s"$base/v1.0/actors/$tpe/$id/method/${urlSegment(method.value)}"

  def invoke[Req: JsonCodec](method: ActorMethodName, data: Req)[Resp: JsonCodec]: Resp =
    val body = summon[JsonCodec[Req]].encode(data)
    val responseStr = post(methodUrl(method), Some(body), sidecar)
    decodeResponse[Resp](actorType, method, responseStr)

  def invoke[Resp: JsonCodec](method: ActorMethodName): Resp =
    val responseStr = post(methodUrl(method), None, sidecar)
    decodeResponse[Resp](actorType, method, responseStr)

  def invokeVoid(method: ActorMethodName): Unit =
    post(methodUrl(method), None, sidecar): Unit

@scala.caps.assumeSafe
private[internal] object ActorCapabilityImpl:

  /** Percent-encode one path segment (or query key/value) of a raw sidecar URL, shared with the other raw-fetch call
    * sites. Domain values (state keys, actor ids, method/topic names, ...) are interpolated into URLs here, so reserved
    * characters (space, `/`, `?`, `#`, `%`, ...) must be encoded or they corrupt the request path; daprd decodes the
    * escapes back before routing.
    */
  private[internal] def urlSegment(value: String): String =
    js.URIUtils.encodeURIComponent(value)

  /** Base URL of the sidecar HTTP API (scheme://host:port, no trailing slash), shared with the other raw-fetch call
    * sites (see `StateCapabilityImpl.getWithETag`).
    */
  private[internal] def httpBase(sidecar: SidecarConfig): String =
    val uri = sidecar.httpEndpoint
    val scheme = uri.getScheme match
      case null => "http"
      case s    => s
    val host = uri.getHost match
      case null => "localhost"
      case h    => h
    val port = uri.getPort match
      case -1 if scheme == "https" => 443
      case -1                      => 80
      case p                       => p
    s"$scheme://$host:$port"

  /** Headers common to every raw sidecar call: the `dapr-api-token` header when configured (the same header the SDK and
    * the JVM client send) plus our content type.
    */
  private[internal] def baseHeaders(sidecar: SidecarConfig): js.Dictionary[String] =
    val headers = js.Dictionary("Content-Type" -> "application/json")
    sidecar.apiToken.foreach(t => headers("dapr-api-token") = t.value)
    headers

  /** POST `body` (if any) and return the response text; throw on HTTP >= 400 with the same message shape as the JVM
    * `HttpActorContext.postJson` (`"Dapr API error $code at $url: $errBody"`).
    */
  private def post(url: String, body: Option[String], sidecar: SidecarConfig): String =
    val init = new facade.FetchRequestInit(
      method = "POST",
      headers = baseHeaders(sidecar),
      body = body.fold[js.UndefOr[String]](js.undefined)(b => b),
    )
    val response = JsAwait.await(facade.NodeGlobals.fetch(url, init))
    val text = JsAwait.await(response.text())
    if response.status >= 400 then throw new RuntimeException(s"Dapr API error ${response.status} at $url: $text")
    text

  /** Decode an actor response, mirroring the JVM twin: a `null`/empty body reaches the codec as the empty string, and a
    * decode failure is wrapped in [[JsonDecodeException]] with the actor/method context.
    */
  private def decodeResponse[Resp: JsonCodec](
      actorType: ActorType,
      method: ActorMethodName,
      responseStr: String,
  ): Resp =
    summon[JsonCodec[Resp]].decode(responseStr) match
      case Left(err) =>
        throw JsonDecodeException(
          s"Actor '${actorType.value}/${method.value}' response decode failed: ${err.getMessage}",
          err,
        )
      case Right(v) => v
