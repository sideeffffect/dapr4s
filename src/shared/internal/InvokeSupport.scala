package dapr4s.internal

import dapr4s.*
import dapr4s.invoke.*
import scala.collection.immutable.ArraySeq

/** The platform-independent half of service invocation. Each platform implements only the HTTP-level
  * [[InvokeCapability.Instance.invokeHttp]] (raw HTTP to the sidecar's `/v1.0/invoke` API); the typed `invoke` is built
  * on top of it here, so its error behaviour — [[InvokeHttpException]] on non-2xx, [[AppUnreachableException]] when the
  * target cannot be reached — is the same code on the JVM and on Scala.js.
  */
private[dapr4s] object InvokeSupport:

  private val ContentTypeHeader = "Content-Type"

  /** Typed invoke with a JSON request body. `metadata` are extra HTTP headers; a caller-supplied `Content-Type` (any
    * case) replaces the default `application/json`.
    */
  def invokeTyped[Req: JsonCodec, Resp: JsonCodec](
      target: InvokeCapability.Instance,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod,
      metadata: Map[MetadataKey, MetadataValue],
  ): Resp =
    val body = Charsets.encodeString(summon[JsonCodec[Req]].encode(data), Charsets.Utf8)
    val headers =
      if metadata.keys.exists(_.value.equalsIgnoreCase(ContentTypeHeader)) then metadata
      else metadata + (MetadataKey(ContentTypeHeader) -> MetadataValue("application/json"))
    decodeTyped[Resp](target.appId, method, target.invokeHttp(method, body, httpMethod, headers, Map.empty))

  /** Typed invoke with no request body (GET, no headers). */
  def invokeTypedNoBody[Resp: JsonCodec](target: InvokeCapability.Instance, method: InvokeMethodName): Resp =
    decodeTyped[Resp](
      target.appId,
      method,
      target.invokeHttp(method, ArraySeq.empty, HttpMethod.Get, Map.empty, Map.empty),
    )

  /** Decode a 2xx answer as `Resp` (an empty body reaches the codec as `null`, the JVM SDK's historical empty-`Mono`
    * input); throw [[InvokeHttpException]] for anything else.
    */
  def decodeTyped[Resp: JsonCodec](appId: AppId, method: InvokeMethodName, response: InvokeResponse): Resp =
    if !response.isSuccess then throw InvokeHttpException(appId, method, response.status, response.body)
    JsonCodec.decodeOrThrow[Resp](if response.body.isEmpty then null else response.bodyText)

  /** Throw the translated sidecar error if a non-2xx invoke answer is daprd's own error rather than the app's (see
    * [[DaprErrors.isSidecarInvokeError]]); return normally otherwise. `parsed` is the platform's parse of the body as a
    * Dapr error (`None` when it is not one).
    */
  def throwIfSidecarError(appId: AppId, status: Int, parsed: Option[SidecarError]): Unit =
    if status < 200 || status >= 300 then
      parsed.filter(DaprErrors.isSidecarInvokeError).foreach { err =>
        throw DaprErrors.translate(ErrorContext.App(appId), err, null)
      }

  /** The sidecar URL path + query for invoking `method` on `appId`: `/v1.0/invoke/{appId}/method/{method}?{query}`. The
    * method name is encoded segment by segment, so a multi-segment route (`api/orders`) keeps its `/` separators.
    */
  def invokePath(
      appId: AppId,
      method: InvokeMethodName,
      query: Map[QueryParamName, QueryParamValue],
  ): String =
    val methodPath = method.value.split("/", -1).nn.map(s => percentEncode(s.nn)).mkString("/")
    val queryString =
      if query.isEmpty then ""
      else query.map((k, v) => s"${percentEncode(k.value)}=${percentEncode(v.value)}").mkString("?", "&", "")
    s"/v1.0/invoke/${percentEncode(appId.value)}/method/$methodPath$queryString"

  /** RFC 3986 percent-encoding of one path segment or query component: the unreserved characters (`A-Z a-z 0-9 - . _
    * ~`) stay, every other UTF-8 byte becomes `%XX`. (`java.net.URLEncoder` is not usable: it encodes form data,
    * turning a space into `+`.)
    */
  def percentEncode(s: String): String =
    val sb = new StringBuilder
    Charsets.encodeString(s, Charsets.Utf8).foreach { b =>
      val c = (b & 0xff).toChar
      if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~".indexOf(c.toInt) >= 0
      then sb.append(c)
      else sb.append('%').append("0123456789ABCDEF".charAt((b & 0xf0) >> 4)).append("0123456789ABCDEF".charAt(b & 0x0f))
    }
    sb.toString

  /** Normalise response headers: lower-case names, multiple values joined with `", "`. */
  def normaliseHeaders(raw: Iterable[(String, String)]): Map[MetadataKey, MetadataValue] =
    raw
      .groupMap((k, _) => k.toLowerCase(java.util.Locale.ROOT).nn)((_, v) => v)
      .map((k, vs) => MetadataKey(k) -> MetadataValue(vs.mkString(", ")))
