package dapr4s.derivation

import dapr4s.*

/** Runtime forwarders used by [[Invoke.derive]]-generated code.
  *
  * The macro emits a single flat call to one of these methods per derived method, passing the capability and the
  * `JsonCodec`s as plain explicit arguments. Doing the [[dapr4s.InvokeCapability.invoke]] dance here — in ordinary,
  * hand-written Scala — keeps the generated trees trivial: no synthesised `given` definitions (which the compiler would
  * otherwise lift and capture into the enclosing class) and no need for the macro to reconstruct `invoke`'s interleaved
  * type/`using` clause structure by hand.
  */
@scala.caps.assumeSafe
object InvokeDerivationRuntime:

  /** Forward to the body-bearing `invoke` overload, narrowing the accessor to `appId` first. */
  def invokeBody[Req, Resp](
      cap: AccessInvokeCapability,
      appId: AppId,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod,
      metadata: Map[MetadataKey, MetadataValue],
      reqCodec: JsonCodec[Req],
      respCodec: JsonCodec[Resp],
  ): Resp =
    given JsonCodec[Req] = reqCodec
    given JsonCodec[Resp] = respCodec
    cap.apply(appId).invoke[Req](method, data, httpMethod, metadata)[Resp]

  /** Forward to the no-body `invoke` overload, narrowing the accessor to `appId` first. */
  def invokeNoBody[Resp](
      cap: AccessInvokeCapability,
      appId: AppId,
      method: InvokeMethodName,
      respCodec: JsonCodec[Resp],
  ): Resp =
    given JsonCodec[Resp] = respCodec
    cap.apply(appId).invoke[Resp](method)
