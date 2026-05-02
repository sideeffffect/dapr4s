package dapr.safe.test.unit

import dapr.safe.*
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import scala.collection.mutable

/** In-memory [[DaprScope]] for unit tests — no Docker, no sidecar required. */
@scala.caps.assumeSafe
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

  def lock(storeName: StoreName): DistributedLockCapability =
    checkOpen()
    new MockDistributedLockCapability(storeName, this)

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

  def get[T: JsonCodec](key: String): Option[T] throws DaprStateException =
    checkOpen()
    store.get(key).flatMap { case (json, _) =>
      summon[JsonCodec[T]].decode(json).toOption
    }

  def getWithETag[T: JsonCodec](key: String): StateEntry[T] throws DaprStateException =
    checkOpen()
    store.get(key) match
      case None =>
        StateEntry(None, None)
      case Some((json, etag)) =>
        val v = summon[JsonCodec[T]].decode(json).toOption
        StateEntry(v, Some(ETag(etag)))

  def getBulk[T: JsonCodec](keys: Seq[String]): Map[String, StateEntry[T]] throws DaprStateException =
    checkOpen()
    keys.map(key => key -> getWithETag[T](key)).toMap

  def save[T: JsonCodec](key: String, value: T): Unit throws DaprStateException =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(value)
    etagCounter += 1
    store(key) = (json, etagCounter.toString)

  def saveBulk[T: JsonCodec](entries: Seq[(String, T)]): Unit throws DaprStateException =
    checkOpen()
    entries.foreach { case (key, value) => save[T](key, value) }

  def saveWithETag[T: JsonCodec](key: String, value: T, etag: ETag): Unit throws DaprStateException =
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

  def delete(key: String): Unit throws DaprStateException =
    checkOpen()
    store.remove(key)
    ()

  def deleteWithETag(key: String, etag: ETag): Unit throws DaprStateException =
    checkOpen()
    store.get(key) match
      case Some((_, currentEtag)) if currentEtag != etag.value =>
        throw ETagMismatchException(key, etag)
      case _ =>
        store.remove(key)
        ()

  def transaction(ops: Seq[StateOp]): Unit throws DaprStateException =
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

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] throws DaprStateException =
    checkOpen()
    // Mock implementation: returns all entries (ignores query expression)
    store.values.map { case (json, etag) =>
      val v = summon[JsonCodec[T]].decode(json).toOption
      StateEntry(v, Some(ETag(etag)))
    }.toList

// ---------------------------------------------------------------------------

private class MockPubSubCapability(
    val pubsubName: PubSubName,
    events: mutable.ArrayBuffer[(String, String, String, Map[String, String])],
    scope: MockDaprScope
) extends PubSubCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def publish[T: JsonCodec](topic: Topic, data: T): Unit throws DaprPubSubException =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(data)
    events += ((pubsubName.value, topic.value, json, Map.empty))

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  ): Unit throws DaprPubSubException =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(data)
    events += ((pubsubName.value, topic.value, json, metadata))

  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult throws DaprPubSubException =
    checkOpen()
    entries.foreach { entry =>
      val json = summon[JsonCodec[T]].encode(entry.event)
      events += ((pubsubName.value, topic.value, json, Map.empty))
    }
    BulkPublishResult(List.empty) // mock: all succeed

// ---------------------------------------------------------------------------

private class MockServiceInvocationCapability(scope: MockDaprScope) extends ServiceInvocationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec](appId: AppId, method: String, data: Req)[Resp: JsonCodec]: Resp throws DaprServiceInvocationException =
    checkOpen()
    throw UnsupportedOperationException(
      "MockServiceInvocationCapability does not support invoke — use integration tests"
    )

  def invokeGet[Resp: JsonCodec](appId: AppId, method: String): Resp throws DaprServiceInvocationException =
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

  def get(key: String): String throws DaprSecretsException =
    checkOpen()
    store.getOrElse(key, throw DaprSecretsException(s"Secret '$key' not found"))

  def getBulk(): Map[String, String] throws DaprSecretsException =
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

  def get(keys: Seq[String]): Map[String, ConfigItem] throws DaprConfigurationException =
    checkOpen()
    keys.flatMap(k => store.get(k).map(k -> _)).toMap

// ---------------------------------------------------------------------------

private class MockBindingsCapability(val bindingName: BindingName, scope: MockDaprScope) extends BindingsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def invoke[Req: JsonCodec](operation: String, data: Req)[Resp: JsonCodec]: Option[Resp] throws DaprBindingsException =
    checkOpen()
    None // mock: no binding response

  def invokeOneWay[Req: JsonCodec](operation: String, data: Req): Unit throws DaprBindingsException =
    checkOpen()
    () // mock: fire-and-forget, do nothing

// ---------------------------------------------------------------------------

private class MockDistributedLockCapability(
    val storeName: StoreName,
    scope: MockDaprScope
) extends DistributedLockCapability:
  // Simple in-memory lock state for testing
  private val locks: mutable.Map[String, String] = mutable.Map.empty // resourceId -> lockOwner

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprScope has been closed")

  def tryLock(resourceId: String, lockOwner: String, expirySeconds: Int): Boolean throws DaprLockException =
    checkOpen()
    if locks.contains(resourceId) then false
    else
      locks(resourceId) = lockOwner
      true

  def unlock(resourceId: String, lockOwner: String): UnlockStatus throws DaprLockException =
    checkOpen()
    locks.get(resourceId) match
      case None                              => UnlockStatus.LockNotFound
      case Some(owner) if owner != lockOwner => UnlockStatus.InternalError
      case Some(_) =>
        locks.remove(resourceId)
        UnlockStatus.Success
