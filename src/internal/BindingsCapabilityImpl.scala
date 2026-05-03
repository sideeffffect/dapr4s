package dapr.safe.internal

import dapr.safe.*
import unsafeExceptions.canThrowAny
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class BindingsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val bindingName: BindingName,
) extends BindingsCapability:

  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req)[Resp: JsonCodec]: Option[Resp] =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val rawResp: String | Null = scope.client
      .invokeBinding(bindingName.value, operation.value, reqJson, classOf[String])
      .awaitResult()
    if rawResp == null || rawResp.isEmpty then None
    else Some(decodeResp[Resp](rawResp))

  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req): Unit =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    scope.client.invokeBinding(bindingName.value, operation.value, reqJson).awaitResult(): Unit

  private def decodeResp[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)
