package dapr.safe.internal

import dapr.safe.*
import MonoOps.*
import scala.jdk.CollectionConverters.*

@scala.caps.assumeSafe
private[safe] final class BindingsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val bindingName: BindingName,
) extends BindingsCapability:

  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Metadata = Metadata.empty,
  )[Resp: JsonCodec]: Option[Resp] =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val javaMeta: java.util.Map[String, String] = metadata.toMap.asJava
    val rawResp: String | Null = scope.client
      .invokeBinding(bindingName.value, operation.value, reqJson, javaMeta, classOf[String])
      .awaitResult()
    if rawResp == null || rawResp.isEmpty then None
    else Some(decodeResp[Resp](rawResp))

  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Metadata = Metadata.empty,
  ): Unit =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val javaMeta: java.util.Map[String, String] = metadata.toMap.asJava
    // No Void+metadata overload exists; use the typed overload and discard the result.
    scope.client
      .invokeBinding(bindingName.value, operation.value, reqJson, javaMeta, classOf[String])
      .awaitResult(): Unit

  private def decodeResp[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)
