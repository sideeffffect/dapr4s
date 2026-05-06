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
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Option[Resp] =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val javaMeta = toJavaMeta(metadata)
    val rawResp: String | Null = scope.client
      .invokeBinding(bindingName.value, operation.value, reqJson, javaMeta, classOf[String])
      .awaitResult()
    if rawResp == null || rawResp.isEmpty then None
    else Some(JsonCodec.decodeOrThrow[Resp](rawResp))

  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Unit =
    val reqJson = summon[JsonCodec[Req]].encode(data)
    val javaMeta = toJavaMeta(metadata)
    // No Void+metadata overload exists; use the typed overload and discard the result.
    scope.client
      .invokeBinding(bindingName.value, operation.value, reqJson, javaMeta, classOf[String])
      .awaitResult(): Unit

  private def toJavaMeta(m: Map[MetadataKey, MetadataValue]): java.util.Map[String, String] =
    m.map { case (k, v) => k.value -> v.value }.asJava
