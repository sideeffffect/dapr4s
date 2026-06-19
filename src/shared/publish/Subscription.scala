package dapr4s.publish

import dapr4s.*

/** Existential wrapper for a pub/sub subscription handler.
  *
  * The abstract type member `Payload` binds [[codec]] to a concrete payload type, enabling path-dependent type safety
  * when iterating `DaprApp.subscriptions`. The handler lambda is stored as `AnyRef` (CC-opaque) so the instance has an
  * empty capture set and can live in a plain `List`. Internal dispatch code (in [[dapr4s.internal.DaprAppServer]] and
  * `TestDaprApp`) casts it back using the `Payload` type member under `@assumeSafe`.
  *
  * '''Dual:''' the inbound counterpart of [[PublishCapability]] — a `Subscription` on a [[Topic]] consumes what a
  * publisher sends to that same topic.
  *
  * Use [[Subscription.apply]] to construct instances.
  */
sealed abstract class Subscription:
  type Payload
  val pubsubName: PubSubName
  val topic: Topic
  val route: Route
  val codec: JsonCodec[Payload]
  // When set, the sidecar routes events that exhaust the retry policy to this topic
  // instead of dropping them. Emitted as `deadLetterTopic` in the /dapr/subscribe response.
  val deadLetterTopic: Option[Topic]
  // WHY AnyRef: stores CloudEvent[Payload] => SubscriptionResult with capture set erased.
  // CC tracks captures through typed function fields; AnyRef is opaque so the instance
  // has no CC capture set and can be stored in a plain List[Subscription].
  // Access only from @assumeSafe dispatch code that casts back with the Payload type member.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[Subscription]] values.
  *
  * WHY @assumeSafe: the handler lambda captures DAPR capabilities from the enclosing scope. Inside this `@assumeSafe`
  * companion, we store the lambda as `AnyRef` (`.asInstanceOf[AnyRef]`) to erase its CC capture set, preventing the
  * anonymous class instance from acquiring a capture set and thus allowing it to be returned as a plain `Subscription`.
  * Callers in safe mode are unaffected.
  */
@scala.caps.assumeSafe
object Subscription:

  def apply[T: JsonCodec](pubsubName: PubSubName, topic: Topic, deadLetterTopic: Option[Topic] = None)(
      handler: CloudEvent[T] => SubscriptionResult,
  ): Subscription =
    apply(pubsubName, topic, Route("/" + topic.value), deadLetterTopic)(handler)

  def apply[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      route: Route,
      deadLetterTopic: Option[Topic],
  )(
      handler: CloudEvent[T] => SubscriptionResult,
  ): Subscription =
    // WHY RENAME: val x = x in anonymous class is a Scala self-reference (x's RHS sees
    // the member x, not the outer parameter).  Capture params into fresh local vals first.
    val pn = pubsubName
    val tp = topic
    val rt = route
    val dlt = deadLetterTopic
    val c = summon[JsonCodec[T]]
    new Subscription:
      type Payload = T
      val pubsubName = pn
      val topic = tp
      val route = rt
      val codec = c
      val deadLetterTopic = dlt
      val rawHandler = handler.asInstanceOf[AnyRef]
