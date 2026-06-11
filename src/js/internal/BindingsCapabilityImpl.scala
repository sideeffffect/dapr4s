//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import JsInterop.*

@scala.caps.assumeSafe
private[internal] final class BindingsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val bindingName: BindingName,
) extends BindingsCapability:

  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Option[Resp] =
    val response = invokeRaw(operation, data, metadata)
    // None when the binding returned no payload (undefined/empty), mirroring the JVM's null/empty
    // byte-array check; the empty string is HTTPClient.execute's tryParseJson artifact for an
    // empty response body. Consequently a binding response document that IS the JSON empty string
    // (`""`) cannot be distinguished from an absent body post-SDK and also maps to None — a
    // documented, accepted divergence from the JVM, which sees the raw bytes (see
    // JsInterop.jsonStringOrNull).
    if isAbsent(response) then None
    else Some(JsonCodec.decodeOrThrow[Resp](js.JSON.stringify(response)))

  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Unit =
    invokeRaw(operation, data, metadata): Unit

  // `binding.send` wraps everything into the {operation, data, metadata} body of POST /v1.0/bindings/{name}
  // (implementation/Client/HTTPClient/binding.js) — `data` is embedded as a JS value, so the pre-encoded JSON is
  // parsed first; the wrapper object then serializes as application/json, putting our exact JSON document into
  // `data` like the JVM's byte[] pass-through does. Errors reject the promise and propagate (JVM: DaprException).
  private def invokeRaw[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
  ): js.Any =
    val json = summon[JsonCodec[Req]].encode(data)
    JsAwait.await(scope.client.binding.send(bindingName.value, operation.value, parseJson(json), toDict(metadata)))
