package dapr.safe.test.unit

import dapr.safe.*

import scala.collection.mutable

/** In-memory [[DaprScope]] for unit tests — no Docker, no sidecar required. */
final class MockDaprScope extends DaprScope:

  @volatile private var _closed = false

  def isClosed: Boolean = _closed

  private def checkOpen(): Unit =
    if _closed then throw java.lang.IllegalStateException("MockDaprScope is closed")

  private val storeMap: mutable.Map[String, mutable.Map[String, (String, String)]] =
    mutable.Map.empty // storeName -> key -> (jsonValue, etag)

  // (pubsubName, topic, jsonPayload, metadata)
  private val pubsubEvents: mutable.ArrayBuffer[(String, String, String, Map[String, String])] =
    mutable.ArrayBuffer.empty

  private val secretStores: mutable.Map[String, mutable.Map[String, String]] =
    mutable.Map.empty

  private val configStores: mutable.Map[String, mutable.Map[String, ConfigItem]] =
    mutable.Map.empty

  // ---- Helpers for test setup --------------------------------------------

  def seedState(storeName: String, key: String, jsonValue: String, etag: String = "1"): Unit =
    storeMap.getOrElseUpdate(storeName, mutable.Map.empty)(key) = (jsonValue, etag)

  /** Returns all published events as (pubsubName, topic, jsonPayload, metadata). */
  def publishedEvents: List[(String, String, String, Map[String, String])] = pubsubEvents.toList

  def seedSecret(storeName: String, key: String, value: String): Unit =
    secretStores.getOrElseUpdate(storeName, mutable.Map.empty)(key) = value

  def seedConfig(storeName: String, key: String, item: ConfigItem): Unit =
    configStores.getOrElseUpdate(storeName, mutable.Map.empty)(key) = item

  // ---- DaprScope implementation ------------------------------------------

  def state(storeName: StoreName): StateCapability =
    checkOpen()
    new MockStateCapability(storeName, storeMap.getOrElseUpdate(storeName.value, mutable.Map.empty), this)

  def pubsub(pubsubName: PubSubName): PubSubCapability =
    checkOpen()
    new MockPubSubCapability(pubsubName, pubsubEvents, this)

  def invoker: ServiceInvocationCapability =
    checkOpen()
    new MockServiceInvocationCapability(this)

  def secrets(storeName: SecretStoreName): SecretsCapability =
    checkOpen()
    new MockSecretsCapability(storeName, secretStores.getOrElseUpdate(storeName.value, mutable.Map.empty), this)

  def config(storeName: ConfigStoreName): ConfigurationCapability =
    checkOpen()
    new MockConfigurationCapability(storeName, configStores.getOrElseUpdate(storeName.value, mutable.Map.empty), this)

  def binding(bindingName: BindingName): BindingsCapability =
    checkOpen()
    new MockBindingsCapability(bindingName, this)

  def close(): Unit =
    _closed = true

// ---------------------------------------------------------------------------

private class MockStateCapability(
    val storeName: StoreName,
    store: mutable.Map[String, (String, String)],
    scope: MockDaprScope
) extends StateCapability:

  private var etagCounter: Int = 100

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def get[T: JsonCodec](key: String): Option[T] =
    checkOpen()
    store.get(key).flatMap { case (json, _) =>
      summon[JsonCodec[T]].decode(json).toOption
    }

  def getWithETag[T: JsonCodec](key: String): StateEntry[T] =
    checkOpen()
    store.get(key) match
      case None =>
        StateEntry(None, None)
      case Some((json, etag)) =>
        val v = summon[JsonCodec[T]].decode(json).toOption
        StateEntry(v, Some(ETag(etag)))

  def save[T: JsonCodec](key: String, value: T): Unit =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(value)
    etagCounter += 1
    store(key) = (json, etagCounter.toString)

  def saveWithETag[T: JsonCodec](key: String, value: T, etag: ETag): Unit =
    checkOpen()
    if etag.value.nonEmpty then
      store.get(key) match
        case None => throw ETagMismatchException(key, etag)
        case Some((_, currentEtag)) if currentEtag != etag.value =>
          throw ETagMismatchException(key, etag)
        case _ => // proceed
    val json = summon[JsonCodec[T]].encode(value)
    etagCounter += 1
    store(key) = (json, etagCounter.toString)

  def delete(key: String): Unit =
    checkOpen()
    store.remove(key)
    ()

  def deleteWithETag(key: String, etag: ETag): Unit =
    checkOpen()
    store.get(key) match
      case Some((_, currentEtag)) if currentEtag != etag.value =>
        throw ETagMismatchException(key, etag)
      case _ =>
        store.remove(key)
        ()

  def transaction(ops: Seq[StateOp]): Unit =
    checkOpen()
    // Validate all first, then apply atomically (simple mock — in-memory)
    ops.foreach:
      case StateOp.DeleteOp(key, Some(etag)) =>
        store.get(key).foreach { case (_, currentEtag) =>
          if currentEtag != etag.value then throw ETagMismatchException(key, etag)
        }
      case StateOp.UpsertOp(key, _, Some(etag)) =>
        store.get(key).foreach { case (_, currentEtag) =>
          if currentEtag != etag.value then throw ETagMismatchException(key, etag)
        }
      case _ => ()

    ops.foreach:
      case StateOp.UpsertOp(key, encodedValue, _) =>
        etagCounter += 1
        store(key) = (encodedValue, etagCounter.toString)
      case StateOp.DeleteOp(key, _) =>
        store.remove(key)

// ---------------------------------------------------------------------------

private class MockPubSubCapability(
    val pubsubName: PubSubName,
    events: mutable.ArrayBuffer[(String, String, String, Map[String, String])],
    scope: MockDaprScope
) extends PubSubCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(data)
    events += ((pubsubName.value, topic.value, json, Map.empty))

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  ): Unit =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(data)
    events += ((pubsubName.value, topic.value, json, metadata))

// ---------------------------------------------------------------------------

private class MockServiceInvocationCapability(scope: MockDaprScope) extends ServiceInvocationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec, Resp: JsonCodec](
      appId: AppId,
      method: String,
      data: Req
  ): Resp =
    checkOpen()
    throw UnsupportedOperationException(
      "MockServiceInvocationCapability does not support invoke — use integration tests"
    )

  def invokeGet[Resp: JsonCodec](appId: AppId, method: String): Resp =
    checkOpen()
    throw UnsupportedOperationException(
      "MockServiceInvocationCapability does not support invokeGet — use integration tests"
    )

// ---------------------------------------------------------------------------

private class MockSecretsCapability(
    val storeName: SecretStoreName,
    store: mutable.Map[String, String],
    scope: MockDaprScope
) extends SecretsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(key: String): String =
    checkOpen()
    store.getOrElse(key, throw DaprException(s"Secret '$key' not found"))

  def getBulk(): Map[String, String] =
    checkOpen()
    store.toMap

// ---------------------------------------------------------------------------

private class MockConfigurationCapability(
    val storeName: ConfigStoreName,
    store: mutable.Map[String, ConfigItem],
    scope: MockDaprScope
) extends ConfigurationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(keys: String*): Map[String, ConfigItem] =
    checkOpen()
    keys.flatMap(k => store.get(k).map(k -> _)).toMap

// ---------------------------------------------------------------------------

private class MockBindingsCapability(val bindingName: BindingName, scope: MockDaprScope) extends BindingsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec, Resp: JsonCodec](
      operation: String,
      data: Req
  ): Option[Resp] =
    checkOpen()
    None // mock: no binding response

  def invokeOneWay[Req: JsonCodec](operation: String, data: Req): Unit =
    checkOpen()
    () // mock: fire-and-forget, do nothing
