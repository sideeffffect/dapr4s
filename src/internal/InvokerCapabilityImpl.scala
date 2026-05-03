package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.HttpExtension
import unsafeExceptions.canThrowAny
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class InvokerCapabilityImpl(
    scope: DaprCapabilityImpl,
) extends ServiceInvocationCapability:

  def invoke[Req: JsonCodec](appId: AppId, method: MethodName, data: Req)[Resp: JsonCodec]: Resp =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val rawResp: String | Null = scope.client
      .invokeMethod(
        appId.value,
        method.value,
        reqJson,
        HttpExtension.POST,
        classOf[String],
      )
      .awaitResult()
    decodeResp[Resp](rawResp)

  def invokeGet[Resp: JsonCodec](appId: AppId, method: MethodName): Resp =
    val rawResp: String | Null = scope.client
      .invokeMethod(
        appId.value,
        method.value,
        null,
        HttpExtension.GET,
        classOf[String],
      )
      .awaitResult()
    decodeResp[Resp](rawResp)

  private def decodeResp[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)
