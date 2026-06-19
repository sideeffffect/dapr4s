package dapr4s.publish

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Capability for DAPR pub/sub publish operations against a named component.
  *
  * '''Dual:''' [[Subscription]] is the inbound counterpart — what this capability publishes to a [[Topic]], a
  * `Subscription` on the same topic consumes. (Derivation binds the two through one trait: `Publish.derive` ↔
  * `Subscriptions.deriveChecked`.)
  */
/** Accessor (rung 2) for pub/sub components: an "any component" handle obtained argument-less via
  * [[DaprCapability.publish]], whose [[apply]] narrows to a [[PublishCapability]] bound to one component.
  */
@scala.caps.assumeSafe
trait AccessPublishCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[PublishCapability]] for the named pub/sub component. */
  def apply(pubsubName: PubSubName): PublishCapability^{this}

@scala.caps.assumeSafe
trait PublishCapability extends scala.caps.ExclusiveCapability:
  val pubsubName: PubSubName

  /** Publish `data` to `topic`. */
  def publish[T: JsonCodec](topic: Topic, data: T): Unit

  /** Publish `data` to `topic` with additional metadata headers. */
  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit

  /** Publish multiple entries to `topic` in a single call. */
  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult

/** Companion-object API for [[PublishCapability]].
  *
  * Forwards to the `PublishCapability` in the enclosing `using` context:
  * {{{
  *   def placeOrder(order: Order)(using PublishCapability): Unit =
  *     PublishCapability.publish(Topic("orders"), order)
  * }}}
  */
@scala.caps.assumeSafe
object PublishCapability:
  def publish[T: JsonCodec](topic: Topic, data: T)(using cap: PublishCapability): Unit =
    cap.publish(topic, data)
  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  )(using cap: PublishCapability): Unit =
    cap.publishWithMetadata(topic, data, metadata)
  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]])(using
      cap: PublishCapability,
  ): BulkPublishResult =
    cap.bulkPublish(topic, entries)

