package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions

// ---------------------------------------------------------------------------
// Root capability marker — all DAPR capabilities extend this
// ---------------------------------------------------------------------------

/** Marker trait for all DAPR capabilities.
  * In the Scala 3 CC model, any class can serve as a capability via `^` annotations.
  */
@scala.caps.assumeSafe
sealed trait DaprCapability

// ---------------------------------------------------------------------------
// Individual capability traits
// ---------------------------------------------------------------------------

/** Capability for DAPR state management operations against a named store.
  *
  * Acquired via [[DaprScope.state]].
  */
@scala.caps.assumeSafe
trait StateCapability extends DaprCapability:
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

// ---------------------------------------------------------------------------

/** Capability for DAPR pub/sub publish operations against a named component. */
@scala.caps.assumeSafe
trait PubSubCapability extends DaprCapability:
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

// ---------------------------------------------------------------------------

/** Capability for synchronous service invocation (RPC) via DAPR. */
@scala.caps.assumeSafe
trait ServiceInvocationCapability extends DaprCapability:

  /** Invoke a remote method with a request body (HTTP POST).
    * `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{invoker.invoke(appId, method, requestData)[ResponseType]}}}
    */
  def invoke[Req: JsonCodec](appId: AppId, method: MethodName, data: Req)[Resp: JsonCodec]: Resp throws DaprServiceInvocationException

  /** Invoke a remote method with no request body (HTTP GET). */
  def invokeGet[Resp: JsonCodec](appId: AppId, method: MethodName): Resp throws DaprServiceInvocationException

// ---------------------------------------------------------------------------

/** Capability for reading secrets from a named DAPR secrets store. */
@scala.caps.assumeSafe
trait SecretsCapability extends DaprCapability:
  val storeName: SecretStoreName

  /** Retrieve a single named secret value. Throws [[DaprSecretsException]] if absent. */
  def get(key: SecretKey): String throws DaprSecretsException

  /** Retrieve all secrets in the store as a flat key→value map. */
  def getBulk(): Map[SecretKey, String] throws DaprSecretsException

// ---------------------------------------------------------------------------

/** Capability for reading configuration items from a named DAPR config store. */
@scala.caps.assumeSafe
trait ConfigurationCapability extends DaprCapability:
  val storeName: ConfigStoreName

  /** Retrieve one or more configuration items by key. */
  def get(keys: Seq[ConfigKey]): Map[ConfigKey, ConfigItem] throws DaprConfigurationException

  /** Subscribe to live configuration changes for the given keys.
    *
    * `onChange` is called on a background thread whenever the sidecar delivers
    * an update.  Returns an `AutoCloseable` that stops the subscription when
    * closed.  The subscription is also stopped when the enclosing [[DaprScope]]
    * is closed.
    */
  def subscribe(keys: Seq[ConfigKey])(onChange: ConfigUpdate => Unit): AutoCloseable throws DaprConfigurationException

// ---------------------------------------------------------------------------

/** Capability for invoking DAPR output bindings. */
@scala.caps.assumeSafe
trait BindingsCapability extends DaprCapability:
  val bindingName: BindingName

  /** Invoke a binding operation that may return a response.
    * `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{binding.invoke(operation, requestData)[ResponseType]}}}
    */
  def invoke[Req: JsonCodec](operation: BindingOperation, data: Req)[Resp: JsonCodec]: Option[Resp] throws DaprBindingsException

  /** Fire-and-forget binding invocation (no response expected). */
  def invokeOneWay[Req: JsonCodec](operation: BindingOperation, data: Req): Unit throws DaprBindingsException

// ---------------------------------------------------------------------------

/** Capability for DAPR distributed locking against a named lock store. */
@scala.caps.assumeSafe
trait DistributedLockCapability extends DaprCapability:
  val storeName: StoreName

  /** Try to acquire a lock. Returns true if acquired, false if already held. */
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int): Boolean throws DaprLockException

  /** Release a previously acquired lock. */
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus throws DaprLockException

// ---------------------------------------------------------------------------

/** Capability for invoking methods on a specific Dapr virtual actor instance. */
@scala.caps.assumeSafe
trait ActorCapability extends DaprCapability:
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

// ---------------------------------------------------------------------------

/** Capability for managing Dapr workflow instances (client-side). */
@scala.caps.assumeSafe
trait WorkflowCapability extends DaprCapability:

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
