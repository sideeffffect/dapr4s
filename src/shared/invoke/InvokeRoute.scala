package dapr4s.invoke

import dapr4s.*

/** Existential wrapper for a service-invocation handler.
  *
  * `Req` and `Resp` type members bind [[reqCodec]] and [[respCodec]] to concrete types. The handler is stored as
  * `AnyRef` for the same reasons as [[Subscription.rawHandler]].
  *
  * '''Dual:''' the inbound counterpart of [[InvokeCapability]] — an `InvokeRoute` for an [[InvokeMethodName]] answers
  * the calls a caller makes to that method.
  *
  * Use [[InvokeRoute.apply]] or [[InvokeRoute.withRequest]] to construct instances.
  */
sealed abstract class InvokeRoute:
  type Req
  type Resp
  val methodName: InvokeMethodName
  val reqCodec: JsonCodec[Req]
  val respCodec: JsonCodec[Resp]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef
  // true when the handler expects InvokeRequest[Req] rather than plain Req.
  private[dapr4s] val usesRequestEnvelope: Boolean

/** Factory for [[InvokeRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object InvokeRoute:

  /** Handler receives only the decoded request body. */
  def apply[Q: JsonCodec, R: JsonCodec](methodName: InvokeMethodName)(
      handler: Q => R,
  ): InvokeRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val mn = methodName
    val rc = summon[JsonCodec[Q]]
    val wc = summon[JsonCodec[R]]
    new InvokeRoute:
      type Req = Q
      type Resp = R
      val methodName = mn
      val reqCodec = rc
      val respCodec = wc
      val rawHandler = handler.asInstanceOf[AnyRef]
      val usesRequestEnvelope = false

  /** Handler receives the full [[InvokeRequest]] envelope (method name, HTTP verb, and decoded body). */
  def withRequest[Q: JsonCodec, R: JsonCodec](methodName: InvokeMethodName)(
      handler: InvokeRequest[Q] => R,
  ): InvokeRoute =
    val mn = methodName
    val rc = summon[JsonCodec[Q]]
    val wc = summon[JsonCodec[R]]
    new InvokeRoute:
      type Req = Q
      type Resp = R
      val methodName = mn
      val reqCodec = rc
      val respCodec = wc
      val rawHandler = handler.asInstanceOf[AnyRef]
      val usesRequestEnvelope = true
