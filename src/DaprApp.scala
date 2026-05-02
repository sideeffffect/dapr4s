package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions

/** Immutable, declarative description of all inbound handlers an application exposes.
  *
  * Build a `DaprApp` using the [[Subscription]], [[InvocationRoute]], and [[BindingRoute]]
  * factory objects, then return it from the [[DaprRuntime.serve]] body:
  *
  * {{{
  *   DaprRuntime.serve(appPort = 8080):
  *     val scope = summon[DaprScope]
  *     given StateCapability  = scope.state(StoreName("statestore"))
  *     given PubSubCapability = scope.pubsub(PubSubName("pubsub"))
  *     DaprApp(
  *       subscriptions = List(
  *         Subscription[OrderEvent](PubSubName("pubsub"), Topic("orders")) { event => ... }
  *       ),
  *       invocations = List(
  *         InvocationRoute[OrderRequest, OrderResponse](MethodName("place-order")) { req => ... }
  *       )
  *     )
  * }}}
  *
  * Two `DaprApp` values can be combined with `++` to compose service modules.
  */
final case class DaprApp(
  subscriptions: List[Subscription]    = Nil,
  invocations:   List[InvocationRoute] = Nil,
  bindings:      List[BindingRoute]    = Nil,
  workflows:     List[DaprWorkflow]    = Nil,
  activities:    List[DaprActivity]    = Nil
):
  def ++(other: DaprApp): DaprApp = DaprApp(
    subscriptions ++ other.subscriptions,
    invocations   ++ other.invocations,
    bindings      ++ other.bindings,
    workflows     ++ other.workflows,
    activities    ++ other.activities
  )

/** Existential wrapper for a pub/sub subscription handler.
  *
  * The abstract type member `Payload` binds [[codec]] to a concrete payload type,
  * enabling path-dependent type safety when iterating `DaprApp.subscriptions`.
  * The handler lambda is stored as `AnyRef` (CC-opaque) so the instance has an
  * empty capture set and can live in a plain `List`.  Internal dispatch code
  * (in [[dapr.safe.internal.DaprAppServer]] and [[dapr.safe.test.integration.TestDaprApp]])
  * casts it back using the `Payload` type member under `@assumeSafe`.
  *
  * Use [[Subscription.apply]] to construct instances.
  */
sealed abstract class Subscription:
  type Payload
  val pubsubName:  PubSubName
  val topic:       Topic
  val route:       Route
  val codec:       JsonCodec[Payload]
  // WHY AnyRef: stores CloudEvent[Payload] => SubscriptionResult with capture set erased.
  // CC tracks captures through typed function fields; AnyRef is opaque so the instance
  // has no CC capture set and can be stored in a plain List[Subscription].
  // Access only from @assumeSafe dispatch code that casts back with the Payload type member.
  private[safe] val rawHandler: AnyRef

/** Factory for [[Subscription]] values.
  *
  * WHY @assumeSafe: the handler lambda captures DAPR capabilities from the enclosing
  * scope.  Inside this `@assumeSafe` companion, we store the lambda as `AnyRef`
  * (`.asInstanceOf[AnyRef]`) to erase its CC capture set, preventing the anonymous
  * class instance from acquiring a capture set and thus allowing it to be returned
  * as a plain `Subscription`.  Callers in safe mode are unaffected.
  */
@scala.caps.assumeSafe
object Subscription:

  def apply[T: JsonCodec](pubsubName: PubSubName, topic: Topic)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Subscription =
    apply(pubsubName, topic, Route("/" + topic.value))(handler)

  def apply[T: JsonCodec](pubsubName: PubSubName, topic: Topic, route: Route)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Subscription =
    // WHY RENAME: val x = x in anonymous class is a Scala self-reference (x's RHS sees
    // the member x, not the outer parameter).  Capture params into fresh local vals first.
    val pn = pubsubName
    val tp = topic
    val rt = route
    val c  = summon[JsonCodec[T]]
    new Subscription:
      type Payload   = T
      val pubsubName = pn
      val topic      = tp
      val route      = rt
      val codec      = c
      val rawHandler = handler.asInstanceOf[AnyRef]

/** Existential wrapper for a service-invocation handler.
  *
  * `Req` and `Resp` type members bind [[reqCodec]] and [[respCodec]] to concrete types.
  * The handler is stored as `AnyRef` for the same reasons as [[Subscription.rawHandler]].
  *
  * Use [[InvocationRoute.apply]] to construct instances.
  */
sealed abstract class InvocationRoute:
  type Req
  type Resp
  val methodName: MethodName
  val reqCodec:   JsonCodec[Req]
  val respCodec:  JsonCodec[Resp]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[safe] val rawHandler: AnyRef

/** Factory for [[InvocationRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object InvocationRoute:

  def apply[Q: JsonCodec, R: JsonCodec](methodName: MethodName)(
    handler: Q => R
  ): InvocationRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val mn = methodName
    val rc = summon[JsonCodec[Q]]
    val wc = summon[JsonCodec[R]]
    new InvocationRoute:
      type Req    = Q
      type Resp   = R
      val methodName = mn
      val reqCodec   = rc
      val respCodec  = wc
      val rawHandler = handler.asInstanceOf[AnyRef]

/** Existential wrapper for an input-binding handler.
  *
  * Use [[BindingRoute.apply]] to construct instances.
  */
sealed abstract class BindingRoute:
  type Payload
  val bindingName: BindingName
  val codec:       JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[safe] val rawHandler: AnyRef

/** Factory for [[BindingRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object BindingRoute:

  def apply[T: JsonCodec](bindingName: BindingName)(
    handler: T => Unit
  ): BindingRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val bn = bindingName
    val c  = summon[JsonCodec[T]]
    new BindingRoute:
      type Payload    = T
      val bindingName = bn
      val codec       = c
      val rawHandler  = handler.asInstanceOf[AnyRef]
