package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.HttpExtension
import language.experimental.saferExceptions
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class InvokerCapabilityImpl(
    scope: DaprCapabilityImpl,
) extends ServiceInvocationCapability:

  def invoke[Req: JsonCodec](appId: AppId, method: MethodName, data: Req)[Resp: JsonCodec]: Resp throws
    DaprServiceInvocationException =
    try
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
      JsonCodec.decodeOrThrow[Resp](rawResp) match
        case v => v
    catch
      case e: DaprServiceInvocationException   => throw e
      case e: DaprException                    => throw DaprServiceInvocationException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprServiceInvocationException(e.getMessage.nn, e)

  def invokeGet[Resp: JsonCodec](appId: AppId, method: MethodName): Resp throws DaprServiceInvocationException =
    try
      val rawResp: String | Null = scope.client
        .invokeMethod(
          appId.value,
          method.value,
          null,
          HttpExtension.GET,
          classOf[String],
        )
        .awaitResult()
      JsonCodec.decodeOrThrow[Resp](rawResp) match
        case v => v
    catch
      case e: DaprServiceInvocationException   => throw e
      case e: DaprException                    => throw DaprServiceInvocationException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprServiceInvocationException(e.getMessage.nn, e)
