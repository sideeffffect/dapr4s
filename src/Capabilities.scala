package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions

// ---------------------------------------------------------------------------
// Individual capability traits
// ---------------------------------------------------------------------------

/** Capability for DAPR state management operations against a named store.
  *
  * Acquired via [[DaprCapability.state]].
  */
@scala.caps.assumeSafe
trait StateCapability:
  val storeName: StoreName

  /** Fetch a value; returns `None` if the key does not exist. */
  def get[T: JsonCodec](key: StateKey): Option[T] throws DaprStateException

  /** Fetch a value together with the current server-side ETag. */
  def getWithETag[T: JsonCodec](key: StateKey): StateEntry[T] throws DaprStateException

  /** Fetch multiple values by key in a single call. */
  def getBulk[T: JsonCodec](keys: Seq[StateKey]): Map[StateKey, StateEntry[T]] throws DaprStateException

  /** Unconditionally save a value. */
  def save[T: JsonCodec](key: StateKey, value: T): Unit throws DaprStateException

  /** Save multiple key-value pairs in a single call. */
  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)]): Unit throws DaprStateException

  /** Save a value only if the provided ETag matches the server-side ETag.
    * Throws [[ETagMismatchException]] on conflict.
    */
  def saveWithETag[T: JsonCodec](key: StateKey, value: T, etag: ETag): Unit throws DaprStateException

  /** Unconditionally delete a key (no-op if the key is absent). */
  def delete(key: StateKey): Unit throws DaprStateException

  /** Delete a key only if the provided ETag matches.
    * Throws [[ETagMismatchException]] on conflict.
    */
  def deleteWithETag(key: StateKey, etag: ETag): Unit throws DaprStateException

  /** Execute multiple state operations atomically (all-or-nothing). */
  def transaction(ops: Seq[StateOp]): Unit throws DaprStateException

  /** Query state using a filter expression. */
  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] throws DaprStateException

/** Companion-object API for [[StateCapability]].
  *
  * Each method forwards to the `StateCapability` provided by the enclosing
  * `using` context, so callers never need to name the capability:
  * {{{
  *   def myHandler(key: StateKey)(using StateCapability): String throws Exception =
  *     StateCapability.get[String](key).getOrElse("default")
  * }}}
  */
object StateCapability:
  def get[T: JsonCodec](key: StateKey)(using cap: StateCapability): Option[T] throws DaprStateException =
    cap.get(key)
  def getWithETag[T: JsonCodec](key: StateKey)(using cap: StateCapability): StateEntry[T] throws DaprStateException =
    cap.getWithETag(key)
  def getBulk[T: JsonCodec](keys: Seq[StateKey])(using cap: StateCapability): Map[StateKey, StateEntry[T]] throws DaprStateException =
    cap.getBulk(keys)
  def save[T: JsonCodec](key: StateKey, value: T)(using cap: StateCapability): Unit throws DaprStateException =
    cap.save(key, value)
  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)])(using cap: StateCapability): Unit throws DaprStateException =
    cap.saveBulk(entries)
  def saveWithETag[T: JsonCodec](key: StateKey, value: T, etag: ETag)(using cap: StateCapability): Unit throws DaprStateException =
    cap.saveWithETag(key, value, etag)
  def delete(key: StateKey)(using cap: StateCapability): Unit throws DaprStateException =
    cap.delete(key)
  def deleteWithETag(key: StateKey, etag: ETag)(using cap: StateCapability): Unit throws DaprStateException =
    cap.deleteWithETag(key, etag)
  def transaction(ops: Seq[StateOp])(using cap: StateCapability): Unit throws DaprStateException =
    cap.transaction(ops)
  def queryState[T: JsonCodec](query: StateQuery)(using cap: StateCapability): List[StateEntry[T]] throws DaprStateException =
    cap.queryState(query)

// ---------------------------------------------------------------------------

/** Capability for DAPR pub/sub publish operations against a named component. */
@scala.caps.assumeSafe
trait PubSubCapability:
  val pubsubName: PubSubName

  /** Publish `data` to `topic`. */
  def publish[T: JsonCodec](topic: Topic, data: T): Unit throws DaprPubSubException

  /** Publish `data` to `topic` with additional metadata headers. */
  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  ): Unit throws DaprPubSubException

  /** Publish multiple entries to `topic` in a single call. */
  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult throws DaprPubSubException

/** Companion-object API for [[PubSubCapability]]. */
object PubSubCapability:
  def publish[T: JsonCodec](topic: Topic, data: T)(using cap: PubSubCapability): Unit throws DaprPubSubException =
    cap.publish(topic, data)
  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  )(using cap: PubSubCapability): Unit throws DaprPubSubException =
    cap.publishWithMetadata(topic, data, metadata)
  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]])(using cap: PubSubCapability): BulkPublishResult throws DaprPubSubException =
    cap.bulkPublish(topic, entries)

// ---------------------------------------------------------------------------

/** Capability for synchronous service invocation (RPC) via DAPR. */
@scala.caps.assumeSafe
trait ServiceInvocationCapability:

  /** Invoke a remote method with a request body (HTTP POST).
    * `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{invoker.invoke(appId, method, requestData)[ResponseType]}}}
    */
  def invoke[Req: JsonCodec](appId: AppId, method: MethodName, data: Req)[Resp: JsonCodec]: Resp throws DaprServiceInvocationException

  /** Invoke a remote method with no request body (HTTP GET). */
  def invokeGet[Resp: JsonCodec](appId: AppId, method: MethodName): Resp throws DaprServiceInvocationException

/** Companion-object API for [[ServiceInvocationCapability]]. */
object ServiceInvocationCapability:
  def invoke[Req: JsonCodec](appId: AppId, method: MethodName, data: Req)[Resp: JsonCodec](using cap: ServiceInvocationCapability): Resp throws DaprServiceInvocationException =
    cap.invoke(appId, method, data)[Resp]
  def invokeGet[Resp: JsonCodec](appId: AppId, method: MethodName)(using cap: ServiceInvocationCapability): Resp throws DaprServiceInvocationException =
    cap.invokeGet(appId, method)

// ---------------------------------------------------------------------------

/** Capability for reading secrets from a named DAPR secrets store. */
@scala.caps.assumeSafe
trait SecretsCapability:
  val storeName: SecretStoreName

  /** Retrieve a single named secret value. Throws [[DaprSecretsException]] if absent. */
  def get(key: SecretKey): String throws DaprSecretsException

  /** Retrieve all secrets in the store as a flat key→value map. */
  def getBulk(): Map[SecretKey, String] throws DaprSecretsException

/** Companion-object API for [[SecretsCapability]]. */
object SecretsCapability:
  def get(key: SecretKey)(using cap: SecretsCapability): String throws DaprSecretsException =
    cap.get(key)
  def getBulk()(using cap: SecretsCapability): Map[SecretKey, String] throws DaprSecretsException =
    cap.getBulk()

// ---------------------------------------------------------------------------

/** Capability for reading configuration items from a named DAPR config store. */
@scala.caps.assumeSafe
trait ConfigurationCapability:
  val storeName: ConfigStoreName

  /** Retrieve one or more configuration items by key. */
  def get(keys: Seq[ConfigKey]): Map[ConfigKey, ConfigItem] throws DaprConfigurationException

  /** Subscribe to live configuration changes for the given keys.
    *
    * `onChange` is called on a background thread whenever the sidecar delivers
    * an update.  Returns an `AutoCloseable` that stops the subscription when
    * closed.  The subscription is also stopped when the enclosing [[DaprCapability]]
    * is closed.
    */
  def subscribe(keys: Seq[ConfigKey])(onChange: ConfigUpdate => Unit): AutoCloseable throws DaprConfigurationException

/** Companion-object API for [[ConfigurationCapability]]. */
object ConfigurationCapability:
  def get(keys: Seq[ConfigKey])(using cap: ConfigurationCapability): Map[ConfigKey, ConfigItem] throws DaprConfigurationException =
    cap.get(keys)
  def subscribe(keys: Seq[ConfigKey])(onChange: ConfigUpdate => Unit)(using cap: ConfigurationCapability): AutoCloseable throws DaprConfigurationException =
    cap.subscribe(keys)(onChange)

// ---------------------------------------------------------------------------

/** Capability for invoking DAPR output bindings. */
@scala.caps.assumeSafe
trait BindingsCapability:
  val bindingName: BindingName

  /** Invoke a binding operation that may return a response.
    * `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{binding.invoke(operation, requestData)[ResponseType]}}}
    */
  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req)[Resp: JsonCodec]: Option[Resp] throws DaprBindingsException

  /** Fire-and-forget binding invocation (no response expected). */
  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req): Unit throws DaprBindingsException

/** Companion-object API for [[BindingsCapability]]. */
object BindingsCapability:
  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req)[Resp: JsonCodec](using cap: BindingsCapability): Option[Resp] throws DaprBindingsException =
    cap.invoke(operation, data)[Resp]
  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req)(using cap: BindingsCapability): Unit throws DaprBindingsException =
    cap.invokeOneWay(operation, data)

// ---------------------------------------------------------------------------

/** Capability for DAPR distributed locking against a named lock store. */
@scala.caps.assumeSafe
trait DistributedLockCapability:
  val storeName: StoreName

  /** Try to acquire a lock. Returns true if acquired, false if already held. */
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int): Boolean throws DaprLockException

  /** Release a previously acquired lock. */
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus throws DaprLockException

/** Companion-object API for [[DistributedLockCapability]]. */
object DistributedLockCapability:
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int)(using cap: DistributedLockCapability): Boolean throws DaprLockException =
    cap.tryLock(resourceId, lockOwner, expirySeconds)
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner)(using cap: DistributedLockCapability): UnlockStatus throws DaprLockException =
    cap.unlock(resourceId, lockOwner)

// ---------------------------------------------------------------------------

/** Capability for invoking methods on a specific Dapr virtual actor instance. */
@scala.caps.assumeSafe
trait ActorCapability:
  val actorType: ActorType
  val actorId: ActorId

  /** Invoke an actor method with a request body.
    *
    * {{{
    *   val resp = actor.invoke(MethodName("GetBalance"), req)[BalanceResponse]
    * }}}
    */
  def invoke[Req: JsonCodec](method: MethodName, data: Req)[Resp: JsonCodec]: Resp throws DaprActorException

  /** Invoke an actor method with no request body. */
  def invokeGet[Resp: JsonCodec](method: MethodName): Resp throws DaprActorException

  /** Invoke an actor method that returns no value. */
  def invokeVoid(method: MethodName): Unit throws DaprActorException

/** Companion-object API for [[ActorCapability]]. */
object ActorCapability:
  def invoke[Req: JsonCodec](method: MethodName, data: Req)[Resp: JsonCodec](using cap: ActorCapability): Resp throws DaprActorException =
    cap.invoke(method, data)[Resp]
  def invokeGet[Resp: JsonCodec](method: MethodName)(using cap: ActorCapability): Resp throws DaprActorException =
    cap.invokeGet(method)
  def invokeVoid(method: MethodName)(using cap: ActorCapability): Unit throws DaprActorException =
    cap.invokeVoid(method)

// ---------------------------------------------------------------------------

/** Capability for managing Dapr workflow instances (client-side). */
@scala.caps.assumeSafe
trait WorkflowCapability:

  /** Start a new workflow instance. Returns the generated instance ID. */
  def start(name: WorkflowName): WorkflowInstanceId throws DaprWorkflowException

  /** Start a new workflow instance with a typed input payload. Returns the generated instance ID. */
  def start[I: JsonCodec](name: WorkflowName, input: I): WorkflowInstanceId throws DaprWorkflowException

  /** Start a new workflow instance with a specific instance ID (no input). */
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId): WorkflowInstanceId throws DaprWorkflowException

  /** Start a new workflow instance with a specific instance ID and typed input. */
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I): WorkflowInstanceId throws DaprWorkflowException

  /** Fetch the current status snapshot of a workflow instance.
    * Returns `None` if the instance does not exist.
    */
  def getStatus(instanceId: WorkflowInstanceId): Option[WorkflowSnapshot] throws DaprWorkflowException

  /** Suspend a running workflow instance (can be resumed later). */
  def suspend(instanceId: WorkflowInstanceId): Unit throws DaprWorkflowException

  /** Resume a previously suspended workflow instance. */
  def resume(instanceId: WorkflowInstanceId): Unit throws DaprWorkflowException

  /** Terminate a workflow instance immediately. */
  def terminate(instanceId: WorkflowInstanceId): Unit throws DaprWorkflowException

  /** Send an external event to a waiting workflow instance. */
  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: String, payload: E): Unit throws DaprWorkflowException

  /** Block until the workflow instance completes (or the timeout expires).
    * Returns the final snapshot, or `None` if the instance was not found.
    * Throws `DaprWorkflowException` on timeout.
    */
  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: java.time.Duration): Option[WorkflowSnapshot] throws DaprWorkflowException

  /** Purge the workflow instance state from the state store. Returns `true` if purged. */
  def purge(instanceId: WorkflowInstanceId): Boolean throws DaprWorkflowException

/** Companion-object API for [[WorkflowCapability]]. */
object WorkflowCapability:
  def start(name: WorkflowName)(using cap: WorkflowCapability): WorkflowInstanceId throws DaprWorkflowException =
    cap.start(name)
  def start[I: JsonCodec](name: WorkflowName, input: I)(using cap: WorkflowCapability): WorkflowInstanceId throws DaprWorkflowException =
    cap.start(name, input)
  def startWithId(name: WorkflowName, instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): WorkflowInstanceId throws DaprWorkflowException =
    cap.startWithId(name, instanceId)
  def startWithId[I: JsonCodec](name: WorkflowName, instanceId: WorkflowInstanceId, input: I)(using cap: WorkflowCapability): WorkflowInstanceId throws DaprWorkflowException =
    cap.startWithId(name, instanceId, input)
  def getStatus(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Option[WorkflowSnapshot] throws DaprWorkflowException =
    cap.getStatus(instanceId)
  def suspend(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit throws DaprWorkflowException =
    cap.suspend(instanceId)
  def resume(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit throws DaprWorkflowException =
    cap.resume(instanceId)
  def terminate(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Unit throws DaprWorkflowException =
    cap.terminate(instanceId)
  def raiseEvent[E: JsonCodec](instanceId: WorkflowInstanceId, eventName: String, payload: E)(using cap: WorkflowCapability): Unit throws DaprWorkflowException =
    cap.raiseEvent(instanceId, eventName, payload)
  def waitForCompletion(instanceId: WorkflowInstanceId, timeout: java.time.Duration)(using cap: WorkflowCapability): Option[WorkflowSnapshot] throws DaprWorkflowException =
    cap.waitForCompletion(instanceId, timeout)
  def purge(instanceId: WorkflowInstanceId)(using cap: WorkflowCapability): Boolean throws DaprWorkflowException =
    cap.purge(instanceId)
