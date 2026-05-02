package dapr.safe.internal

import dapr.safe.*
import language.experimental.saferExceptions

@scala.caps.assumeSafe
private[safe] final class BindingsCapabilityImpl(
    scope: DaprScopeImpl,
    val bindingName: BindingName
) extends BindingsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec](operation: String, data: Req)[Resp: JsonCodec]: Option[Resp] throws DaprBindingsException =
    checkOpen()
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      val rawResp: String | Null = scope.client
        .invokeBinding(bindingName.value, operation, reqJson, classOf[String])
        .block()
      if rawResp == null || rawResp.isEmpty then None
      else
        val decoded = JsonCodec.decodeOrThrow[Resp](rawResp) match
          case v => Some(v)
        decoded
    catch
      case e: DaprBindingsException => throw e
      case e: DaprException => throw DaprBindingsException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprBindingsException(e.getMessage.nn, e)

  def invokeOneWay[Req: JsonCodec](operation: String, data: Req): Unit throws DaprBindingsException =
    checkOpen()
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      scope.client.invokeBinding(bindingName.value, operation, reqJson).block(): Unit
    catch
      case e: DaprBindingsException => throw e
      case e: DaprException => throw DaprBindingsException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprBindingsException(e.getMessage.nn, e)
