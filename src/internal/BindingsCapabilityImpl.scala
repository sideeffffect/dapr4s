package dapr4s.internal

import dapr4s.*
import io.dapr.utils.TypeRef
import MonoOps.*
import scala.jdk.CollectionConverters.*
import java.nio.charset.StandardCharsets.UTF_8

@scala.caps.assumeSafe
private[internal] final class BindingsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val bindingName: BindingName,
) extends BindingsCapability:

  import BindingsCapabilityImpl.*

  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Option[Resp] =
    val respStr = bytesToString(invokeRaw(operation, data, metadata))
    if respStr == null || respStr.isEmpty then None
    else Some(JsonCodec.decodeOrThrow[Resp](respStr))

  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Unit =
    invokeRaw(operation, data, metadata): Unit

  // Exchange raw bytes with the sidecar in both directions. Passing a String (or decoding the
  // response as String) would make the SDK's Jackson serializer encode the JSON a SECOND time,
  // so the binding target receives `"{\"k\":..}"` instead of `{"k":..}` (e.g. an HTTP binding
  // POSTs a quoted string and the endpoint rejects it). byte[]-in + TypeRef.BYTE_ARRAY-out skips
  // serialization entirely; dapr4s owns the JSON. Same fix as InvokeCapabilityImpl.invoke.
  private def invokeRaw[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
  ): Array[Byte] | Null =
    val reqBytes = summon[JsonCodec[Req]].encode(data).getBytes(UTF_8)
    val javaMeta = toJavaMeta(metadata)
    scope.client
      .invokeBinding(bindingName.value, operation.value, reqBytes, javaMeta, TypeRef.BYTE_ARRAY)
      .awaitResult()

@scala.caps.assumeSafe
private object BindingsCapabilityImpl:
  private def bytesToString(bytes: Array[Byte] | Null): String | Null =
    if bytes == null then null else new String(bytes, UTF_8)

  private def toJavaMeta(m: Map[MetadataKey, MetadataValue]): java.util.Map[String, String] =
    m.map { case (k, v) => k.value -> v.value }.asJava
