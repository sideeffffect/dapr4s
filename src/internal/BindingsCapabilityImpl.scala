package dapr.safe.internal

// NOTE: @assumeSafe would be applied here once Scala 3 stable supports it.
// Currently this annotation is only available in nightly Scala 3 builds.

import dapr.safe.*

private[safe] final class BindingsCapabilityImpl(
    scope: DaprScopeImpl,
    val bindingName: BindingName
) extends BindingsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec, Resp: JsonCodec](
      operation: String,
      data: Req
  ): Option[Resp] =
    checkOpen()
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      val rawResp: String = scope.daprClient
        .invokeBinding(bindingName.value, operation, reqJson, classOf[String])
        .block()
      if rawResp == null || rawResp.isEmpty then None
      else Some(JsonCodec.decodeOrThrow[Resp](rawResp))
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def invokeOneWay[Req: JsonCodec](operation: String, data: Req): Unit =
    checkOpen()
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      scope.daprClient.invokeBinding(bindingName.value, operation, reqJson).block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)
