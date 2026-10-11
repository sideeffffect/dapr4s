package dapr4s.publish

import dapr4s.*

/** Universal capability for DAPR pub/sub publishing: reaches **any** pub/sub component, so every operation names its
  * component.
  *
  * Obtained via [[DaprCapability.publish]]. Narrow to one component with [[apply]] (`publish(pubsubName)`), which
  * yields a [[PublishCapability.Instance]].
  *
  * '''Dual:''' [[Subscription]] is the inbound counterpart — what this capability publishes to a [[Topic]], a
  * `Subscription` on the same topic consumes. (Derivation binds the two through one trait: `Publish.derive` ↔
  * `Subscriptions.deriveChecked`.)
  */
@scala.caps.assumeSafe
trait PublishCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to the named pub/sub component. */
  def instance(pubsubName: PubSubName): PublishCapability.Instance^{this}

  /** Publish `data` to `topic` on the named component. */
  def publish[T: JsonCodec](pubsubName: PubSubName, topic: Topic, data: T): Unit

  /** Publish `data` to `topic` on the named component with additional metadata headers. */
  def publishWithMetadata[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit

  /** Publish multiple entries to `topic` on the named component in a single call. */
  def bulkPublish[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      entries: Seq[BulkPublishEntry[T]],
  ): BulkPublishResult

/** The [[PublishCapability]] family: the component-bound [[PublishCapability.Instance]] and the companion-object
  * forwarders for both interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def placeOrder(order: Order)(using PublishCapability.Instance): Unit =
  *     PublishCapability.Instance.publish(Topic("orders"), order)
  * }}}
  */
@scala.caps.assumeSafe
object PublishCapability:

  /** Capability bound to one named pub/sub component. Obtained via `publish(pubsubName)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The pub/sub component this capability is bound to. */
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

  /** Forwarders for [[PublishCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def publish[T: JsonCodec](topic: Topic, data: T)(using cap: Instance): Unit =
      cap.publish(topic, data)
    def publishWithMetadata[T: JsonCodec](
        topic: Topic,
        data: T,
        metadata: Map[MetadataKey, MetadataValue],
    )(using cap: Instance): Unit =
      cap.publishWithMetadata(topic, data, metadata)
    def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]])(using
        cap: Instance,
    ): BulkPublishResult =
      cap.bulkPublish(topic, entries)

  /** Narrow the universal capability in scope to one component and return it. */
  def instance(pubsubName: PubSubName)(using cap: PublishCapability): Instance^{cap} =
    cap.instance(pubsubName)
  def publish[T: JsonCodec](pubsubName: PubSubName, topic: Topic, data: T)(using cap: PublishCapability): Unit =
    cap.publish(pubsubName, topic, data)
  def publishWithMetadata[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  )(using cap: PublishCapability): Unit =
    cap.publishWithMetadata(pubsubName, topic, data, metadata)
  def bulkPublish[T: JsonCodec](pubsubName: PubSubName, topic: Topic, entries: Seq[BulkPublishEntry[T]])(using
      cap: PublishCapability,
  ): BulkPublishResult =
    cap.bulkPublish(pubsubName, topic, entries)
