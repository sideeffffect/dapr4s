//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import dapr4s.invoke.*
import scala.collection.immutable.ArraySeq
import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Int8Array}
import dapr4styped.node.globalsMod.global as NodeGlobals
import dapr4styped.undiciTypes.fetchMod.{RequestInit, RequestRedirect}

@scala.caps.assumeSafe
private object InvokeCapabilityImpl:

  /** fetch only auto-uppercases the six spec-listed methods (notably NOT "patch"), so the verb is spelled out. */
  private def verb(m: HttpMethod): String =
    m match
      case HttpMethod.Get     => "GET"
      case HttpMethod.Post    => "POST"
      case HttpMethod.Put     => "PUT"
      case HttpMethod.Delete  => "DELETE"
      case HttpMethod.Patch   => "PATCH"
      case HttpMethod.Head    => "HEAD"
      case HttpMethod.Options => "OPTIONS"

  /** Copy the bytes into a fresh `ArrayBuffer`, the `BodyInit` member fetch sends verbatim. */
  private def toArrayBuffer(bytes: ArraySeq[Byte]): ArrayBuffer =
    val typed = new Int8Array(bytes.length)
    var i = 0
    bytes.foreach { b =>
      typed(i) = b
      i += 1
    }
    typed.buffer

  /** Copy a response `ArrayBuffer` out into an immutable byte sequence (the array never escapes, so wrapping it is
    * safe).
    */
  private def fromArrayBuffer(buffer: ArrayBuffer): ArraySeq[Byte] =
    val typed = new Int8Array(buffer)
    val out = new Array[Byte](typed.length)
    var i = 0
    while i < typed.length do
      out(i) = typed(i)
      i += 1
    ArraySeq.unsafeWrapArray(out)

/** Service invocation over the raw sidecar HTTP API (`{verb} /v1.0/invoke/{appId}/method/{method}`) with Node's `fetch`
  * — the SDK's `invoker.invoke` is not used at all.
  *
  * WHY: the SDK cannot express either half of the invoke contract. `HTTPClient.execute` rejects every status outside
  * 200–399 with the body squeezed into a JSON-stringified `Error.message` and drops all response headers, so
  * `invokeHttp`'s status/headers/body result cannot be built from it, and it RESOLVES 3xx while the JVM twin's typed
  * `invoke` treats them as failures. It also drops JS-falsy request bodies (`if (params?.body)` — the JSON documents
  * `0`, `false`, `null`, `""`). The raw API passes the target app's status, headers and body through unchanged; the JVM
  * twin uses the same API through `java.net.http`, and the typed `invoke` on both is the shared
  * [[InvokeSupport.invokeTyped]] on top of [[invokeHttp]], so the two platforms behave identically.
  */
@scala.caps.assumeSafe
private[internal] final class InvokeCapabilityImpl(
    scope: DaprCapabilityImpl,
    val appId: AppId,
) extends InvokeCapability.Instance:

  import InvokeCapabilityImpl.*

  private val ctx = ErrorContext.App(appId)

  def invoke[Req: JsonCodec](
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Resp =
    InvokeSupport.invokeTyped[Req, Resp](this, method, data, httpMethod, metadata)

  def invoke[Resp: JsonCodec](method: InvokeMethodName): Resp =
    InvokeSupport.invokeTypedNoBody[Resp](this, method)

  def invokeHttp(
      method: InvokeMethodName,
      body: ArraySeq[Byte] = ArraySeq.empty,
      httpMethod: HttpMethod = HttpMethod.Post,
      headers: Map[MetadataKey, MetadataValue] = Map.empty,
      query: Map[QueryParamName, QueryParamValue] = Map.empty,
  ): InvokeResponse =
    val url = ActorCapabilityImpl.httpBase(scope.sidecar) + InvokeSupport.invokePath(appId, method, query)
    // The API token first; caller headers on top. A caller header that collides with a base header REPLACES it (drop
    // the base pair first): filling fetch Headers from a pairs array APPENDS, and duplicate names are combined as
    // "v1, v2". baseHeaders also carries Content-Type: application/json, which is NOT wanted here — invokeHttp sends
    // bytes as is and adds no content type (the typed invoke passes its own).
    val requestHeaders = ActorCapabilityImpl
      .baseHeaders(scope.sidecar)
      .filterNot(pair => pair(0).equalsIgnoreCase("Content-Type"))
      .filterNot(pair => headers.keys.exists(_.value.equalsIgnoreCase(pair(0))))
    headers.foreach { (k, v) => requestHeaders.push(js.Array(k.value, v.value)): Unit }
    // redirect: "manual" — a 3xx is the target's answer, returned as is (fetch follows redirects by default; the JVM
    // client never does).
    val init = RequestInit().setMethod(verb(httpMethod)).setHeaders(requestHeaders).setRedirect(RequestRedirect.manual)
    if body.nonEmpty then init.setBody(toArrayBuffer(body)): Unit
    val response = JsAwait.await(NodeGlobals.fetch(url, init), ctx)
    // Always consume the body (Node fetch keep-alive invariant — see HttpActorContext.postJson).
    val bytes = fromArrayBuffer(JsAwait.await(response.arrayBuffer(), ctx))
    val status = response.status.toInt
    if status < 200 || status >= 300 then
      InvokeSupport.throwIfSidecarError(
        appId,
        status,
        JsErrors.parseErrorBody(status, Charsets.decodeString(bytes, Charsets.Utf8)),
      )
    val rawHeaders = List.newBuilder[(String, String)]
    response.headers.forEach((value, name, _) => rawHeaders += name -> value: Unit)
    InvokeResponse(status, InvokeSupport.normaliseHeaders(rawHeaders.result()), bytes)
