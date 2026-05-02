package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.HttpExtension
import language.experimental.saferExceptions
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class InvokerCapabilityImpl(
    scope: DaprScopeImpl
) extends ServiceInvocationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec](appId: AppId, method: String, data: Req)[Resp: JsonCodec]: Resp throws DaprServiceInvocationException =
    checkOpen()
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      val rawResp: String | Null = scope.client
        .invokeMethod(
          appId.value,
          method,
          reqJson,
          HttpExtension.POST,
          classOf[String]
        )
        .awaitResult()
      JsonCodec.decodeOrThrow[Resp](rawResp) match
        case v => v
    catch
      case e: DaprServiceInvocationException => throw e
      case e: DaprException => throw DaprServiceInvocationException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprServiceInvocationException(e.getMessage.nn, e)

  def invokeGet[Resp: JsonCodec](appId: AppId, method: String): Resp throws DaprServiceInvocationException =
    checkOpen()
    try
      val rawResp: String | Null = scope.client
        .invokeMethod(
          appId.value,
          method,
          null,
          HttpExtension.GET,
          classOf[String]
        )
        .awaitResult()
      JsonCodec.decodeOrThrow[Resp](rawResp) match
        case v => v
    catch
      case e: DaprServiceInvocationException => throw e
      case e: DaprException => throw DaprServiceInvocationException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprServiceInvocationException(e.getMessage.nn, e)
