package dapr4s.bindings

import dapr4s.*

/** Existential wrapper for an input-binding handler.
  *
  * '''Dual:''' the inbound counterpart of [[BindingsCapability]] (output bindings). These are independent directions on
  * a binding component, not a request/response contract: a `BindingRoute` receives payloads delivered to a
  * [[BindingName]], whereas the capability issues [[BindingOperation]]s.
  *
  * Use [[BindingRoute.apply]] to construct instances.
  */
sealed abstract class BindingRoute:
  type Payload
  val bindingName: BindingName
  val codec: JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[BindingRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object BindingRoute:

  def apply[T: JsonCodec](bindingName: BindingName)(
      handler: T => Unit,
  ): BindingRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val bn = bindingName
    val c = summon[JsonCodec[T]]
    new BindingRoute:
      type Payload = T
      val bindingName = bn
      val codec = c
      val rawHandler = handler.asInstanceOf[AnyRef]
