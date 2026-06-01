package dapr4s.internal

import dapr4s.*
import io.dapr.client.domain.HttpExtension
import MonoOps.*
import scala.jdk.CollectionConverters.*
import java.nio.charset.StandardCharsets.UTF_8

private object HttpMethodConversions:
  def toJava(m: HttpMethod): HttpExtension =
    m match
      case HttpMethod.Get     => HttpExtension.GET
      case HttpMethod.Post    => HttpExtension.POST
      case HttpMethod.Put     => HttpExtension.PUT
      case HttpMethod.Delete  => HttpExtension.DELETE
      case HttpMethod.Patch   => HttpExtension.PATCH
      case HttpMethod.Head    => HttpExtension.HEAD
      case HttpMethod.Options => new HttpExtension(io.dapr.client.DaprHttp.HttpMethods.OPTIONS)

@scala.caps.assumeSafe
private[dapr4s] final class InvokerCapabilityImpl(
    scope: DaprCapabilityImpl,
) extends ServiceInvocationCapability:

  import HttpMethodConversions.*

  def invoke[Req: JsonCodec](
      appId: AppId,
      method: MethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Resp =
    // Exchange raw bytes with the sidecar in both directions: the Dapr SDK's serializer would otherwise
    // re-serialize our already-encoded JSON String on the way out (double-encoding the request into a JSON
    // string) and Jackson-parse the response into a String on the way back (mangling it before our own
    // codec sees it). The byte[]-in/byte[]-out overload skips serialization entirely; dapr4s owns the JSON.
    val reqBytes = summon[JsonCodec[Req]].encode(data).getBytes(UTF_8)
    val javaMeta = toJavaMeta(metadata)
    JsonCodec.decodeOrThrow[Resp](
      bytesToString(
        scope.client
          .invokeMethod(appId.value, method.value, reqBytes, toJava(httpMethod), javaMeta)
          .awaitResult(),
      ),
    )

  def invoke[Resp: JsonCodec](appId: AppId, method: MethodName): Resp =
    JsonCodec.decodeOrThrow[Resp](
      bytesToString(
        scope.client
          .invokeMethod(appId.value, method.value, Array.emptyByteArray, HttpExtension.GET, emptyMeta)
          .awaitResult(),
      ),
    )

  private def bytesToString(bytes: Array[Byte] | Null): String | Null =
    if bytes == null then null else new String(bytes, UTF_8)

  private val emptyMeta: java.util.Map[String, String] = java.util.Collections.emptyMap()

  private def toJavaMeta(m: Map[MetadataKey, MetadataValue]): java.util.Map[String, String] =
    m.map { case (k, v) => k.value -> v.value }.asJava
