package dapr.safe.internal

import dapr.safe.*
import language.experimental.saferExceptions
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class BindingsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val bindingName: BindingName
) extends BindingsCapability:

  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req)[Resp: JsonCodec]: Option[Resp] throws DaprBindingsException =
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      val rawResp: String | Null = scope.client
        .invokeBinding(bindingName.value, operation.value, reqJson, classOf[String])
        .awaitResult()
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

  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req): Unit throws DaprBindingsException =
    try
      val reqJson = summon[JsonCodec[Req]].encode(data)
      scope.client.invokeBinding(bindingName.value, operation.value, reqJson).awaitResult(): Unit
    catch
      case e: DaprBindingsException => throw e
      case e: DaprException => throw DaprBindingsException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprBindingsException(e.getMessage.nn, e)
