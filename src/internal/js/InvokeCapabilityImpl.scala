//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import JsInterop.*

@scala.caps.assumeSafe
private object InvokeCapabilityImpl:

  /** dapr4s [[HttpMethod]] → the SDK's lowercase `HttpMethod` string values (`enum/HttpMethod.enum.js`).
    *
    * The SDK enum only declares get/delete/post/put/patch; `"head"` and `"options"` are still correct because the value
    * flows verbatim into `HTTPClient.execute`, which upper-cases it and hands it to fetch (`clientOptions.method =
    * params?.method.toLocaleUpperCase()`).
    */
  private def toJsMethod(m: HttpMethod): String =
    m match
      case HttpMethod.Get     => "get"
      case HttpMethod.Post    => "post"
      case HttpMethod.Put     => "put"
      case HttpMethod.Delete  => "delete"
      case HttpMethod.Patch   => "patch"
      case HttpMethod.Head    => "head"
      case HttpMethod.Options => "options"

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
    // Metadata maps to extra HTTP headers (InvokerOptions.headers — the JVM impl's invokeMethod
    // metadata are headers too). The explicit Content-Type: application/json header makes the
    // SDK's serializeHttp JSON.stringify the parsed value rather than inferring text/plain for
    // JSON scalar payloads — same wire-format reasoning as PublishCapabilityImpl.publish.
    val headers = toDict(metadata)
    headers("Content-Type") = "application/json"
    val response = JsAwait.await(
      scope.client.invoker.invoke(
        appId.value,
        method.value,
        toJsMethod(httpMethod),
        parseJson(summon[JsonCodec[Req]].encode(data)),
        new facade.InvokerOptions(headers = headers),
      ),
    )
    // An empty response body surfaces as "" (HTTPClient.execute's tryParseJson); jsonStringOrNull
    // maps it to null so the codec sees the same input as on the JVM (empty Mono → null bytes).
    JsonCodec.decodeOrThrow[Resp](jsonStringOrNull(response))

  def invoke[Resp: JsonCodec](appId: AppId, method: InvokeMethodName): Resp =
    val response = JsAwait.await(
      scope.client.invoker.invoke(appId.value, method.value, "get", js.undefined, new facade.InvokerOptions()),
    )
    JsonCodec.decodeOrThrow[Resp](jsonStringOrNull(response))
