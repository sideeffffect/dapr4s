package dapr.safe.test.unit

import dapr.safe.*

import scala.collection.mutable

/** In-memory [[DaprCapability]] for unit tests — no Docker, no sidecar required. */
@scala.caps.assumeSafe
final class MockDaprCapability extends DaprCapability:

  @volatile private var _closed = false

  def isClosed: Boolean = _closed

  private def checkOpen(): Unit =
    if _closed then throw java.lang.IllegalStateException("MockDaprCapability is closed")

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

  // ---- DaprCapability implementation ------------------------------------------

  // WHY ^{this}: mirrors DaprCapabilityImpl — sub-capabilities extend ExclusiveCapability, so
  // CC infers ^{fresh}. Explicit ^{this} satisfies the override check against the trait declaration.

  def state(storeName: StoreName): StateCapability^{this} =
    checkOpen()
    new MockStateCapability(storeName, storeMap.getOrElseUpdate(storeName.value, mutable.Map.empty), this)
      .asInstanceOf[StateCapability]

  def pubsub(pubsubName: PubSubName): PubSubCapability^{this} =
    checkOpen()
    new MockPubSubCapability(pubsubName, pubsubEvents, this).asInstanceOf[PubSubCapability]

  def invoker: ServiceInvocationCapability^{this} =
    checkOpen()
    new MockServiceInvocationCapability(this).asInstanceOf[ServiceInvocationCapability]

  def secrets(storeName: SecretStoreName): SecretsCapability^{this} =
    checkOpen()
    new MockSecretsCapability(storeName, secretStores.getOrElseUpdate(storeName.value, mutable.Map.empty), this)
      .asInstanceOf[SecretsCapability]

  def config(storeName: ConfigStoreName): ConfigurationCapability^{this} =
    checkOpen()
    new MockConfigurationCapability(storeName, configStores.getOrElseUpdate(storeName.value, mutable.Map.empty), this)
      .asInstanceOf[ConfigurationCapability]

  def binding(bindingName: BindingName): BindingsCapability^{this} =
    checkOpen()
    new MockBindingsCapability(bindingName, this).asInstanceOf[BindingsCapability]

  def lock(storeName: StoreName): DistributedLockCapability^{this} =
    checkOpen()
    new MockDistributedLockCapability(storeName, this).asInstanceOf[DistributedLockCapability]

  def actor(actorType: ActorType, actorId: ActorId): ActorCapability^{this} =
    checkOpen()
    new MockActorCapability(actorType, actorId, this).asInstanceOf[ActorCapability]

  def workflow: WorkflowCapability^{this} =
    checkOpen()
    new MockWorkflowCapability(this).asInstanceOf[WorkflowCapability]

  def close(): Unit =
    _closed = true

// ---------------------------------------------------------------------------

private class MockStateCapability(
    val storeName: StoreName,
    store: mutable.Map[String, (String, String)],
    scope: MockDaprCapability,
) extends StateCapability:

  private var etagCounter: Int = 100

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def get[T: JsonCodec](key: StateKey): Option[T] =
    checkOpen()
    store.get(key.value).flatMap { case (json, _) =>
      summon[JsonCodec[T]].decode(json).toOption
    }

  def getWithETag[T: JsonCodec](key: StateKey): StateEntry[T] =
    checkOpen()
    store.get(key.value) match
      case None =>
        StateEntry(None, None)
      case Some((json, etag)) =>
        val v = summon[JsonCodec[T]].decode(json).toOption
        StateEntry(v, Some(ETag(etag)))

  def getBulk[T: JsonCodec](keys: Seq[StateKey]): Map[StateKey, StateEntry[T]] =
    checkOpen()
    keys.map(key => key -> getWithETag[T](key)).toMap

  def save[T: JsonCodec](key: StateKey, value: T): Unit =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(value)
    etagCounter += 1
    store(key.value) = (json, etagCounter.toString)

  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)]): Unit =
    checkOpen()
    entries.foreach { case (key, value) => save[T](key, value) }

  def saveWithETag[T: JsonCodec](key: StateKey, value: T, etag: ETag): Option[ETagMismatchException] =
    checkOpen()
    if etag.value.nonEmpty then
      store.get(key.value) match
        case None =>
          return Some(ETagMismatchException(key, etag))
        case Some((_, currentEtag)) if currentEtag != etag.value =>
          return Some(ETagMismatchException(key, etag))
        case _ => // proceed
    val json = summon[JsonCodec[T]].encode(value)
    etagCounter += 1
    store(key.value) = (json, etagCounter.toString)
    None

  def delete(key: StateKey): Unit =
    checkOpen()
    store.remove(key.value)
    ()

  def deleteWithETag(key: StateKey, etag: ETag): Option[ETagMismatchException] =
    checkOpen()
    store.get(key.value) match
      case Some((_, currentEtag)) if currentEtag != etag.value =>
        Some(ETagMismatchException(key, etag))
      case _ =>
        store.remove(key.value)
        None

  def transaction(ops: Seq[StateOp]): Unit =
    checkOpen()
    // Validate all first, then apply atomically (simple mock — in-memory)
    ops.foreach:
      case StateOp.DeleteOp(key, Some(etag)) =>
        store.get(key.value).foreach { case (_, currentEtag) =>
          if currentEtag != etag.value then throw ETagMismatchException(key, etag)
        }
      case StateOp.UpsertOp(key, _, Some(etag)) =>
        store.get(key.value).foreach { case (_, currentEtag) =>
          if currentEtag != etag.value then throw ETagMismatchException(key, etag)
        }
      case _ => ()

    ops.foreach:
      case StateOp.UpsertOp(key, encodedValue, _) =>
        etagCounter += 1
        store(key.value) = (encodedValue, etagCounter.toString)
      case StateOp.DeleteOp(key, _) =>
        store.remove(key.value)

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] =
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
    scope: MockDaprCapability,
) extends PubSubCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(data)
    events += ((pubsubName.value, topic.value, json, Map.empty))

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String],
  ): Unit =
    checkOpen()
    val json = summon[JsonCodec[T]].encode(data)
    events += ((pubsubName.value, topic.value, json, metadata))

  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult =
    checkOpen()
    entries.foreach { entry =>
      val json = summon[JsonCodec[T]].encode(entry.event)
      events += ((pubsubName.value, topic.value, json, Map.empty))
    }
    BulkPublishResult(List.empty) // mock: all succeed

// ---------------------------------------------------------------------------

private class MockServiceInvocationCapability(scope: MockDaprCapability) extends ServiceInvocationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def invoke[Req: JsonCodec](appId: AppId, method: MethodName, data: Req)[Resp: JsonCodec]: Resp =
    checkOpen()
    throw UnsupportedOperationException(
      "MockServiceInvocationCapability does not support invoke — use integration tests",
    )

  def invokeGet[Resp: JsonCodec](appId: AppId, method: MethodName): Resp =
    checkOpen()
    throw UnsupportedOperationException(
      "MockServiceInvocationCapability does not support invokeGet — use integration tests",
    )

// ---------------------------------------------------------------------------

private class MockSecretsCapability(
    val storeName: SecretStoreName,
    store: mutable.Map[String, String],
    scope: MockDaprCapability,
) extends SecretsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def get(key: SecretKey): Option[String] =
    checkOpen()
    store.get(key.value)

  def getBulk(): Map[SecretKey, String] =
    checkOpen()
    store.map { case (k, v) => SecretKey(k) -> v }.toMap

// ---------------------------------------------------------------------------

private class MockConfigurationCapability(
    val storeName: ConfigStoreName,
    store: mutable.Map[String, ConfigItem],
    scope: MockDaprCapability,
) extends ConfigurationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def get(keys: Seq[ConfigKey]): Map[ConfigKey, ConfigItem] =
    checkOpen()
    keys.flatMap(k => store.get(k.value).map(ConfigKey(k.value) -> _)).toMap

  def subscribe(keys: Seq[ConfigKey])(onChange: ConfigUpdate => Unit): AutoCloseable =
    checkOpen()
    () => () // mock: no-op subscription

// ---------------------------------------------------------------------------

private class MockBindingsCapability(val bindingName: BindingName, scope: MockDaprCapability)
    extends BindingsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req)[Resp: JsonCodec]: Option[Resp] =
    checkOpen()
    None // mock: no binding response

  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req): Unit =
    checkOpen()
    () // mock: fire-and-forget, do nothing

// ---------------------------------------------------------------------------

private class MockDistributedLockCapability(
    val storeName: StoreName,
    scope: MockDaprCapability,
) extends DistributedLockCapability:
  // Simple in-memory lock state for testing
  private val locks: mutable.Map[String, String] = mutable.Map.empty // resourceId -> lockOwner

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int): Boolean =
    checkOpen()
    if locks.contains(resourceId.value) then false
    else
      locks(resourceId.value) = lockOwner.value
      true

  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    checkOpen()
    locks.get(resourceId.value) match
      case None                                    => UnlockStatus.LockNotFound
      case Some(owner) if owner != lockOwner.value => UnlockStatus.InternalError
      case Some(_)                                 =>
        locks.remove(resourceId.value)
        UnlockStatus.Success

// ---------------------------------------------------------------------------

private class MockActorCapability(
    val actorType: ActorType,
    val actorId: ActorId,
    scope: MockDaprCapability,
) extends ActorCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  def invoke[Req: JsonCodec](method: MethodName, data: Req)[Resp: JsonCodec]: Resp =
    checkOpen()
    throw UnsupportedOperationException(
      "MockActorCapability does not support invoke — use integration tests",
    )

  def invokeGet[Resp: JsonCodec](method: MethodName): Resp =
    checkOpen()
    throw UnsupportedOperationException(
      "MockActorCapability does not support invokeGet — use integration tests",
    )

  def invokeVoid(method: MethodName): Unit =
    checkOpen()
    throw UnsupportedOperationException(
      "MockActorCapability does not support invokeVoid — use integration tests",
    )

// ---------------------------------------------------------------------------

private class MockWorkflowCapability(scope: MockDaprCapability) extends WorkflowCapability:

  private val instances: mutable.Map[String, WorkflowSnapshot] = mutable.Map.empty

  private def checkOpen(): Unit =
    if scope.isClosed then throw java.lang.IllegalStateException("Capability is closed: DaprCapability has been closed")

  private def genId(): String = java.util.UUID.randomUUID().toString

  def start(name: WorkflowName): WorkflowInstanceId =
    checkOpen()
    val id = WorkflowInstanceId(genId())
    instances(id.value) = mockSnapshot(name, id)
    id

  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId =
    start(name)

  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId =
    checkOpen()
    instances(instanceId.value) = mockSnapshot(name, instanceId)
    instanceId

  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId =
    startWithId(name, instanceId)

  def getStatus(instanceId: WorkflowInstanceId): Option[WorkflowSnapshot] =
    checkOpen()
    instances.get(instanceId.value)

  def suspend(instanceId: WorkflowInstanceId): Unit =
    checkOpen()
    updateStatus(instanceId, WorkflowStatus.Suspended)

  def resume(instanceId: WorkflowInstanceId): Unit =
    checkOpen()
    updateStatus(instanceId, WorkflowStatus.Running)

  def terminate(instanceId: WorkflowInstanceId): Unit =
    checkOpen()
    updateStatus(instanceId, WorkflowStatus.Terminated)

  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: EventName, payload: E): Unit =
    checkOpen()
    () // mock: no-op

  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: java.time.Duration): Option[WorkflowSnapshot] =
    checkOpen()
    instances.get(instanceId.value)

  def purge(instanceId: WorkflowInstanceId): Boolean =
    checkOpen()
    instances.remove(instanceId.value).isDefined

  private def mockSnapshot(name: WorkflowName, id: WorkflowInstanceId): WorkflowSnapshot =
    val now = java.time.Instant.now()
    WorkflowSnapshot(name, id, WorkflowStatus.Running, now, now, None, None)

  private def updateStatus(instanceId: WorkflowInstanceId, status: WorkflowStatus): Unit =
    instances.get(instanceId.value).foreach { snap =>
      instances(instanceId.value) = snap.copy(status = status, lastUpdatedAt = java.time.Instant.now())
    }

// ---------------------------------------------------------------------------

/** In-memory [[ActorContext]] for unit tests.
  *
  * Pre-seed state with [[seedState]]; inspect it after method invocations with [[stateSnapshot]],
  * [[registeredReminders]], and [[registeredTimers]].
  */
@scala.caps.assumeSafe
final class MockActorContext extends ActorContext:

  private val store: mutable.Map[String, String] = mutable.Map.empty

  // reminder name → (dataJson, dueTime, period)
  private val reminders: mutable.Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    mutable.Map.empty

  // timer name → (dataJson, dueTime, period)
  private val timers: mutable.Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    mutable.Map.empty

  def seedState[T: JsonCodec](key: StateKey, value: T): Unit =
    store(key.value) = summon[JsonCodec[T]].encode(value)

  def stateSnapshot: Map[String, String] = store.toMap

  def registeredReminders: Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    reminders.toMap

  def registeredTimers: Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    timers.toMap

  def get[T: JsonCodec](key: StateKey): Option[T] =
    store.get(key.value).flatMap(json => summon[JsonCodec[T]].decode(json).toOption)

  def set[T: JsonCodec](key: StateKey, value: T): Unit =
    store(key.value) = summon[JsonCodec[T]].encode(value)

  def remove(key: StateKey): Unit =
    store.remove(key.value)
    ()

  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: java.time.Duration,
      period: Option[java.time.Duration] = None,
  ): Unit =
    reminders(name.value) = (summon[JsonCodec[T]].encode(data), dueTime, period)

  def unregisterReminder(name: ReminderName): Unit =
    reminders.remove(name.value)
    ()

  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: java.time.Duration,
      period: Option[java.time.Duration] = None,
  ): Unit =
    timers(name.value) = (summon[JsonCodec[T]].encode(data), dueTime, period)

  def unregisterTimer(name: TimerName): Unit =
    timers.remove(name.value)
    ()
