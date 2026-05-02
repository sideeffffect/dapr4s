package dapr.safe.internal

// NOTE: @assumeSafe would be applied here once Scala 3 stable supports it.
// Currently this annotation is only available in nightly Scala 3 builds.

import dapr.safe.*
import io.dapr.client.domain.HttpExtension

private[safe] final class InvokerCapabilityImpl(
    scope: DaprScopeImpl
) extends ServiceInvocationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec, Resp: JsonCodec](
      appId: AppId,
      method: String,
      data: Req
  ): Resp =
    checkOpen()
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      val rawResp: String = scope.daprClient
        .invokeMethod(
          appId.value,
          method,
          reqJson,
          HttpExtension.POST,
          classOf[String]
        )
        .block()
      JsonCodec.decodeOrThrow[Resp](rawResp)
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def invokeGet[Resp: JsonCodec](appId: AppId, method: String): Resp =
    checkOpen()
    try
      val rawResp: String = scope.daprClient
        .invokeMethod(
          appId.value,
          method,
          null,
          HttpExtension.GET,
          classOf[String]
        )
        .block()
      JsonCodec.decodeOrThrow[Resp](rawResp)
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)
