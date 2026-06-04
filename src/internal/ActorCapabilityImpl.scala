package dapr4s.internal

import dapr4s.*
import io.dapr.actors.ActorId as JavaActorId
import io.dapr.actors.client.{
  ActorClient as JavaActorClient,
  ActorProxy as JavaActorProxy,
  ActorProxyBuilder as JavaActorProxyBuilder,
}
import dapr4s.internal.MonoOps.awaitResult
import unsafeExceptions.canThrowAny

/** Client-side capability for invoking methods on a specific Dapr virtual actor instance.
  *
  * The actor proxy communicates with the Dapr sidecar via gRPC. A [[JavaActorClient]] (and its underlying gRPC channel)
  * is shared across all actor invocations within the same [[dapr4s.DaprCapability]].
  *
  * Serialization uses raw `byte[]` pass-through: the request value is encoded to JSON by our [[JsonCodec]], sent as raw
  * bytes, and the response bytes are decoded by the same codec — bypassing the Java SDK's Jackson-based serializer
  * entirely.
  */
@scala.caps.assumeSafe
private[dapr4s] final class ActorCapabilityImpl(
    val actorType: ActorType,
    val actorId: ActorId,
    private val proxy: JavaActorProxy,
) extends ActorCapability:

  def invoke[Req: JsonCodec](method: MethodName, data: Req)[Resp: JsonCodec]: Resp =
    val requestBytes = summon[JsonCodec[Req]].encode(data).getBytes(java.nio.charset.StandardCharsets.UTF_8).nn
    val rawResult = proxy.invokeMethod(method.value, requestBytes, classOf[Array[Byte]]).awaitResult()
    ActorCapabilityImpl.decodeResponse[Resp](actorType, method, rawResult)

  def invoke[Resp: JsonCodec](method: MethodName): Resp =
    val rawResult = proxy.invokeMethod(method.value, classOf[Array[Byte]]).awaitResult()
    ActorCapabilityImpl.decodeResponse[Resp](actorType, method, rawResult)

  def invokeVoid(method: MethodName): Unit =
    proxy.invokeMethod(method.value).awaitResult()

@scala.caps.assumeSafe
private[dapr4s] object ActorCapabilityImpl:

  def decodeResponse[Resp: JsonCodec](actorType: ActorType, method: MethodName, rawResult: Array[Byte] | Null): Resp =
    val bytes = if rawResult == null then Array.empty[Byte] else rawResult
    val responseStr = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    summon[JsonCodec[Resp]].decode(responseStr) match
      case Left(err) =>
        throw JsonDecodeException(
          s"Actor '${actorType.value}/${method.value}' response decode failed: ${err.getMessage}",
          err,
        )
      case Right(v) => v

  def build(actorType: ActorType, actorId: ActorId, actorClient: JavaActorClient): ActorCapabilityImpl =
    val builder = new JavaActorProxyBuilder[JavaActorProxy](
      actorType.value,
      classOf[JavaActorProxy],
      actorClient,
    )
    val proxy = builder.build(new JavaActorId(actorId.value)).nn
    new ActorCapabilityImpl(actorType, actorId, proxy)
