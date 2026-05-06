package dapr.safe

import scala.concurrent.duration.FiniteDuration

// ---------------------------------------------------------------------------
// Individual capability traits
// ---------------------------------------------------------------------------

/** Capability for DAPR state management operations against a named store.
  *
  * Acquired via [[DaprCapability.state]].
  */
@scala.caps.assumeSafe
trait StateCapability extends scala.caps.ExclusiveCapability:
  val storeName: StoreName

  /** Fetch a value; returns `None` if the key does not exist.
    *
    * @param consistency
    *   read consistency level; [[StateConsistency.Default]] uses the store's own default
    */
  def get[T: JsonCodec](key: StateKey, consistency: StateConsistency = StateConsistency.Default): Option[T]

  /** Fetch a value together with the current server-side ETag.
    *
    * @param consistency
    *   read consistency level; [[StateConsistency.Default]] uses the store's own default
    */
  def getWithETag[T: JsonCodec](key: StateKey, consistency: StateConsistency = StateConsistency.Default): StateEntry[T]

  /** Fetch multiple values by key in a single call. */
  def getBulk[T: JsonCodec](keys: Seq[StateKey]): Map[StateKey, StateEntry[T]]

  /** Unconditionally save a value. */
  def save[T: JsonCodec](key: StateKey, value: T): Unit

  /** Save multiple key-value pairs in a single call. */
  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)]): Unit

  /** Save a value only if the provided ETag matches the server-side ETag. Returns `None` on success, `Some(e)` if the
    * ETag did not match.
    *
    * @param metadata
    *   optional metadata forwarded to the state store
    * @param consistency
    *   write consistency level; [[StateConsistency.Default]] uses the store's own default
    * @param concurrency
    *   concurrency control; [[StateConcurrency.FirstWrite]] is the typical safe default for optimistic locking
    */
  def saveWithETag[T: JsonCodec](
      key: StateKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException]

  /** Unconditionally delete a key (no-op if the key is absent). */
  def delete(key: StateKey): Unit

  /** Delete a key only if the provided ETag matches. Returns `None` on success, `Some(e)` if the ETag did not match.
    *
    * @param consistency
    *   write consistency level; [[StateConsistency.Default]] uses the store's own default
    * @param concurrency
    *   concurrency control; [[StateConcurrency.FirstWrite]] is the typical safe default for optimistic locking
    */
  def deleteWithETag(
      key: StateKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException]

  /** Execute multiple state operations atomically (all-or-nothing). */
  def transaction(ops: Seq[StateOp]): Unit

  /** Query state using a filter expression. */
  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]]

/** Companion-object API for [[StateCapability]].
  *
  * Each method forwards to the `StateCapability` provided by the enclosing `using` context, so callers never need to
  * name the capability:
  * {{{
  *   def myHandler(key: StateKey)(using StateCapability): String throws Exception =
  *     StateCapability.get[String](key).getOrElse("default")
  * }}}
  */
@scala.caps.assumeSafe
object StateCapability:
  def get[T: JsonCodec](
      key: StateKey,
      consistency: StateConsistency = StateConsistency.Default,
  )(using cap: StateCapability): Option[T] =
    cap.get(key, consistency)
  def getWithETag[T: JsonCodec](
      key: StateKey,
      consistency: StateConsistency = StateConsistency.Default,
  )(using cap: StateCapability): StateEntry[T] =
    cap.getWithETag(key, consistency)
  def getBulk[T: JsonCodec](keys: Seq[StateKey])(using
      cap: StateCapability,
  ): Map[StateKey, StateEntry[T]] =
    cap.getBulk(keys)
  def save[T: JsonCodec](key: StateKey, value: T)(using cap: StateCapability): Unit =
    cap.save(key, value)
  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)])(using cap: StateCapability): Unit =
    cap.saveBulk(entries)
  def saveWithETag[T: JsonCodec](
      key: StateKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  )(using cap: StateCapability): Option[ETagMismatchException] =
    cap.saveWithETag(key, value, etag, metadata, consistency, concurrency)
  def delete(key: StateKey)(using cap: StateCapability): Unit =
    cap.delete(key)
  def deleteWithETag(
      key: StateKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  )(using cap: StateCapability): Option[ETagMismatchException] =
    cap.deleteWithETag(key, etag, consistency, concurrency)
  def transaction(ops: Seq[StateOp])(using cap: StateCapability): Unit =
    cap.transaction(ops)
  def queryState[T: JsonCodec](query: StateQuery)(using
      cap: StateCapability,
  ): List[StateEntry[T]] =
    cap.queryState(query)

// ---------------------------------------------------------------------------

/** Capability for DAPR pub/sub publish operations against a named component. */
@scala.caps.assumeSafe
trait PubSubCapability extends scala.caps.ExclusiveCapability:
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

/** Companion-object API for [[PubSubCapability]].
  *
  * Forwards to the `PubSubCapability` in the enclosing `using` context:
  * {{{
  *   def placeOrder(order: Order)(using PubSubCapability): Unit =
  *     PubSubCapability.publish(Topic("orders"), order)
  * }}}
  */
@scala.caps.assumeSafe
object PubSubCapability:
  def publish[T: JsonCodec](topic: Topic, data: T)(using cap: PubSubCapability): Unit =
    cap.publish(topic, data)
  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  )(using cap: PubSubCapability): Unit =
    cap.publishWithMetadata(topic, data, metadata)
  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]])(using
      cap: PubSubCapability,
  ): BulkPublishResult =
    cap.bulkPublish(topic, entries)

// ---------------------------------------------------------------------------

/** Capability for synchronous service invocation (RPC) via DAPR. */
@scala.caps.assumeSafe
trait ServiceInvocationCapability extends scala.caps.ExclusiveCapability:

  /** Invoke a remote method with a request body. `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{invoker.invoke(appId, method, requestData)[ResponseType]}}}
    *
    * @param httpMethod
    *   HTTP verb to use; defaults to [[HttpMethod.Post]]
    * @param metadata
    *   optional gRPC/HTTP metadata headers forwarded to the target service
    */
  def invoke[Req: JsonCodec](
      appId: AppId,
      method: MethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Resp

  /** Invoke a remote method with no request body (GET, no metadata).
    *
    * Use the body-bearing overload to pass a non-default HTTP verb or metadata headers.
    */
  def invoke[Resp: JsonCodec](appId: AppId, method: MethodName): Resp

/** Companion-object API for [[ServiceInvocationCapability]].
  *
  * Forwards to the `ServiceInvocationCapability` in the enclosing `using` context:
  * {{{
  *   def getUser(id: String)(using ServiceInvocationCapability): User =
  *     ServiceInvocationCapability.invoke(AppId("user-service"), MethodName("get"), id)[User]
  * }}}
  */
@scala.caps.assumeSafe
object ServiceInvocationCapability:
  def invoke[Req: JsonCodec](
      appId: AppId,
      method: MethodName,
      data: Req,
      httpMethod: HttpMethod = HttpMethod.Post,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec](using cap: ServiceInvocationCapability): Resp =
    cap.invoke(appId, method, data, httpMethod, metadata)[Resp]
  def invoke[Resp: JsonCodec](appId: AppId, method: MethodName)(using cap: ServiceInvocationCapability): Resp =
    cap.invoke(appId, method)

// ---------------------------------------------------------------------------

/** Capability for reading secrets from a named DAPR secrets store. */
@scala.caps.assumeSafe
trait SecretsCapability extends scala.caps.ExclusiveCapability:
  val storeName: SecretStoreName

  /** Retrieve a single named secret value. Returns `None` if absent.
    *
    * @param metadata
    *   optional metadata passed to the secrets backend
    */
  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty): Option[SecretValue]

  /** Retrieve all secrets in the store as a flat key→value map.
    *
    * @param metadata
    *   optional metadata passed to the secrets backend
    */
  def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[SecretKey, SecretValue]

/** Companion-object API for [[SecretsCapability]].
  *
  * Forwards to the `SecretsCapability` in the enclosing `using` context:
  * {{{
  *   def dbPassword()(using SecretsCapability): String =
  *     SecretsCapability.get(SecretKey("db-password")).getOrElse("default")
  * }}}
  */
@scala.caps.assumeSafe
object SecretsCapability:
  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
      cap: SecretsCapability,
  ): Option[SecretValue] =
    cap.get(key, metadata)
  def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
      cap: SecretsCapability,
  ): Map[SecretKey, SecretValue] =
    cap.getBulk(metadata)

// ---------------------------------------------------------------------------

/** Capability for reading configuration items from a named DAPR config store. */
@scala.caps.assumeSafe
trait ConfigurationCapability extends scala.caps.ExclusiveCapability:
  val storeName: ConfigStoreName

  /** Retrieve one or more configuration items by key.
    *
    * @param metadata
    *   optional metadata passed to the configuration backend
    */
  def get(keys: Seq[ConfigKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[ConfigKey, ConfigItem]

  /** Subscribe to live configuration changes for the given keys.
    *
    * `onChange` is called on a background thread whenever the sidecar delivers an update. Returns an `AutoCloseable`
    * that stops the subscription when closed. The subscription is also stopped when the enclosing [[DaprCapability]] is
    * closed.
    *
    * @param metadata
    *   optional metadata passed to the configuration backend
    */
  def subscribe(keys: Seq[ConfigKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
      onChange: ConfigUpdate => Unit,
  ): AutoCloseable

/** Companion-object API for [[ConfigurationCapability]].
  *
  * Forwards to the `ConfigurationCapability` in the enclosing `using` context:
  * {{{
  *   def featureFlag()(using ConfigurationCapability): Boolean =
  *     ConfigurationCapability.get(Seq(ConfigKey("feature-x")))
  *       .get(ConfigKey("feature-x")).exists(_.value == "true")
  * }}}
  */
@scala.caps.assumeSafe
object ConfigurationCapability:
  def get(keys: Seq[ConfigKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
      cap: ConfigurationCapability,
  ): Map[ConfigKey, ConfigItem] =
    cap.get(keys, metadata)
  def subscribe(keys: Seq[ConfigKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
      onChange: ConfigUpdate => Unit,
  )(using cap: ConfigurationCapability): AutoCloseable =
    cap.subscribe(keys, metadata)(onChange)

// ---------------------------------------------------------------------------

/** Capability for invoking DAPR output bindings. */
@scala.caps.assumeSafe
trait BindingsCapability extends scala.caps.ExclusiveCapability:
  val bindingName: BindingName

  /** Invoke a binding operation that may return a response. `Req` is inferred from `data`; `Resp` is specified at the
    * call site: {{{binding.invoke(operation, requestData)[ResponseType]}}}
    *
    * @param metadata
    *   optional metadata forwarded to the binding component
    */
  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec]: Option[Resp]

  /** Fire-and-forget binding invocation (no response expected).
    *
    * @param metadata
    *   optional metadata forwarded to the binding component
    */
  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Unit

/** Companion-object API for [[BindingsCapability]].
  *
  * Forwards to the `BindingsCapability` in the enclosing `using` context:
  * {{{
  *   def sendEmail(msg: EmailRequest)(using BindingsCapability): Unit =
  *     BindingsCapability.invokeOneWay(BindingOperation("create"), msg)
  * }}}
  */
@scala.caps.assumeSafe
object BindingsCapability:
  def invoke[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )[Resp: JsonCodec](using cap: BindingsCapability): Option[Resp] =
    cap.invoke(operation, data, metadata)[Resp]
  def invokeOneWay[Req: JsonCodec](
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using cap: BindingsCapability): Unit =
    cap.invokeOneWay(operation, data, metadata)

// ---------------------------------------------------------------------------

/** Capability for DAPR distributed locking against a named lock store. */
@scala.caps.assumeSafe
trait DistributedLockCapability extends scala.caps.ExclusiveCapability:
  val storeName: StoreName

  /** Try to acquire a lock. Returns true if acquired, false if already held. */
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int): Boolean

  /** Release a previously acquired lock. */
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus

/** Companion-object API for [[DistributedLockCapability]].
  *
  * Forwards to the `DistributedLockCapability` in the enclosing `using` context:
  * {{{
  *   def withLock(resource: LockResourceId, owner: LockOwner)(using DistributedLockCapability): Boolean =
  *     if DistributedLockCapability.tryLock(resource, owner, expirySeconds = 30) then
  *       try doWork(); true
  *       finally DistributedLockCapability.unlock(resource, owner)
  *     else false
  * }}}
  */
@scala.caps.assumeSafe
object DistributedLockCapability:
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int)(using
      cap: DistributedLockCapability,
  ): Boolean =
    cap.tryLock(resourceId, lockOwner, expirySeconds)
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner)(using
      cap: DistributedLockCapability,
  ): UnlockStatus =
    cap.unlock(resourceId, lockOwner)

// ---------------------------------------------------------------------------

/** Capability for invoking methods on a specific Dapr virtual actor instance. */
@scala.caps.assumeSafe
trait ActorCapability extends scala.caps.ExclusiveCapability:
  val actorType: ActorType
  val actorId: ActorId

  /** Invoke an actor method with a request body.
    *
    * {{{
    *   val resp = actor.invoke(MethodName("GetBalance"), req)[BalanceResponse]
    * }}}
    */
  def invoke[Req: JsonCodec](method: MethodName, data: Req)[Resp: JsonCodec]: Resp

  /** Invoke an actor method with no request body. */
  def invoke[Resp: JsonCodec](method: MethodName): Resp

  /** Invoke an actor method that returns no value. */
  def invokeVoid(method: MethodName): Unit

/** Companion-object API for [[ActorCapability]].
  *
  * Forwards to the `ActorCapability` in the enclosing `using` context:
  * {{{
  *   def getBalance(id: ActorId)(using cap: ActorCapability): Balance =
  *     ActorCapability.invoke(MethodName("GetBalance"), BalanceRequest(id))[Balance]
  * }}}
  */
@scala.caps.assumeSafe
object ActorCapability:
  def invoke[Req: JsonCodec](method: MethodName, data: Req)[Resp: JsonCodec](using
      cap: ActorCapability,
  ): Resp =
    cap.invoke(method, data)[Resp]
  def invoke[Resp: JsonCodec](method: MethodName)(using cap: ActorCapability): Resp =
    cap.invoke(method)
  def invokeVoid(method: MethodName)(using cap: ActorCapability): Unit =
    cap.invokeVoid(method)

// ---------------------------------------------------------------------------

/** Capability for managing Dapr workflow instances (client-side). */
@scala.caps.assumeSafe
trait WorkflowCapability extends scala.caps.ExclusiveCapability:

  /** Start a new workflow instance. Returns the generated instance ID. */
  def start(name: WorkflowName): WorkflowInstanceId

  /** Start a new workflow instance with a typed input payload. Returns the generated instance ID. */
  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId

  /** Start a new workflow instance with a specific instance ID (no input). */
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId

  /** Start a new workflow instance with a specific instance ID and typed input. */
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId

  /** Fetch the current status snapshot of a workflow instance. Returns `None` if the instance does not exist.
    */
  def getStatus(instanceId: WorkflowInstanceId): Option[WorkflowSnapshot]

  /** Suspend a running workflow instance (can be resumed later). */
  def suspend(instanceId: WorkflowInstanceId): Unit

  /** Resume a previously suspended workflow instance. */
  def resume(instanceId: WorkflowInstanceId): Unit

  /** Terminate a workflow instance immediately. */
  def terminate(instanceId: WorkflowInstanceId): Unit

  /** Send an external event to a waiting workflow instance. */
  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: EventName, payload: E): Unit

  /** Block until the workflow instance completes (or the timeout expires). Returns the final snapshot, or `None` if the
    * instance was not found.
    */
  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: FiniteDuration): Option[WorkflowSnapshot]

  /** Purge the workflow instance state from the state store. Returns `true` if purged. */
  def purge(instanceId: WorkflowInstanceId): Boolean

/** Companion-object API for [[WorkflowCapability]].
  *
  * Forwards to the `WorkflowCapability` in the enclosing `using` context:
  * {{{
  *   def processOrder(order: Order)(using WorkflowCapability): WorkflowInstanceId =
  *     WorkflowCapability.start[Order](
  *       WorkflowName(classOf[OrderWorkflow].getCanonicalName),
  *       order,
  *     )
  * }}}
  */
@scala.caps.assumeSafe
object WorkflowCapability:
  def start(name: WorkflowName)(using cap: WorkflowCapability): WorkflowInstanceId =
    cap.start(name)
  def start[I: JsonCodec](name: WorkflowName, input: I)(using
      cap: WorkflowCapability,
  ): WorkflowInstanceId =
    cap.start(name, input)
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId)(using
      cap: WorkflowCapability,
  ): WorkflowInstanceId =
    cap.startWithId(name, instanceId)
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I)(using
      cap: WorkflowCapability,
  ): WorkflowInstanceId =
    cap.startWithId(name, instanceId, input)
  def getStatus(instanceId: WorkflowInstanceId)(using
      cap: WorkflowCapability,
  ): Option[WorkflowSnapshot] =
    cap.getStatus(instanceId)
  def suspend(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit =
    cap.suspend(instanceId)
  def resume(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit =
    cap.resume(instanceId)
  def terminate(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit =
    cap.terminate(instanceId)
  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: EventName, payload: E)(using
      cap: WorkflowCapability,
  ): Unit =
    cap.raiseEvent(instanceId, eventName, payload)
  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: FiniteDuration)(using
      cap: WorkflowCapability,
  ): Option[WorkflowSnapshot] =
    cap.waitForCompletion(instanceId, timeout)
  def purge(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Boolean =
    cap.purge(instanceId)
