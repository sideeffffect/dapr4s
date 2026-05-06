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
      metadata: Metadata = Metadata.empty,
  )[Resp: JsonCodec]: Resp =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val javaMeta: java.util.Map[String, String] = metadata.toMap.asJava
    val rawResp: String | Null = scope.client
      .invokeMethod(appId.value, method.value, reqJson, toJava(httpMethod), javaMeta, classOf[String])
      .awaitResult()
    decodeResp[Resp](rawResp)

  def invokeGet[Resp: JsonCodec](
      appId: AppId,
      method: MethodName,
      httpMethod: HttpMethod = HttpMethod.Get,
      metadata: Metadata = Metadata.empty,
  ): Resp =
    val javaMeta: java.util.Map[String, String] = metadata.toMap.asJava
    val rawResp: String | Null = scope.client
      .invokeMethod(appId.value, method.value, toJava(httpMethod), javaMeta, classOf[String])
      .awaitResult()
    decodeResp[Resp](rawResp)

  private def decodeResp[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)
