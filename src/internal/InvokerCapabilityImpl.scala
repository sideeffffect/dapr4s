package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.HttpExtension
import MonoOps.*
import scala.jdk.CollectionConverters.*

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
private[safe] final class InvokerCapabilityImpl(
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
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val javaMeta = toJavaMeta(metadata)
    JsonCodec.decodeOrThrow[Resp](
      scope.client
        .invokeMethod(appId.value, method.value, reqJson, toJava(httpMethod), javaMeta, classOf[String])
        .awaitResult(),
    )

  def invoke[Resp: JsonCodec](appId: AppId, method: MethodName): Resp =
    JsonCodec.decodeOrThrow[Resp](
      scope.client
        .invokeMethod(appId.value, method.value, HttpExtension.GET, java.util.Collections.emptyMap(), classOf[String])
        .awaitResult(),
    )

  private def toJavaMeta(m: Map[MetadataKey, MetadataValue]): java.util.Map[String, String] =
    m.map { case (k, v) => k.value -> v.value }.asJava
