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
  def get[T: JsonCodec](key: String): Option[T] throws DaprStateException

  /** Fetch a value together with the current server-side ETag. */
  def getWithETag[T: JsonCodec](key: String): StateEntry[T] throws DaprStateException

  /** Fetch multiple values by key in a single call. */
  def getBulk[T: JsonCodec](keys: Seq[String]): Map[String, StateEntry[T]] throws DaprStateException

  /** Unconditionally save a value. */
  def save[T: JsonCodec](key: String, value: T): Unit throws DaprStateException

  /** Save multiple key-value pairs in a single call. */
  def saveBulk[T: JsonCodec](entries: Seq[(String, T)]): Unit throws DaprStateException

  /** Save a value only if the provided ETag matches the server-side ETag.
    * Throws [[ETagMismatchException]] on conflict.
    */
  def saveWithETag[T: JsonCodec](key: String, value: T, etag: ETag): Unit throws DaprStateException

  /** Unconditionally delete a key (no-op if the key is absent). */
  def delete(key: String): Unit throws DaprStateException

  /** Delete a key only if the provided ETag matches.
    * Throws [[ETagMismatchException]] on conflict.
    */
  def deleteWithETag(key: String, etag: ETag): Unit throws DaprStateException

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
    * {{{invoker.invoke(appId, "method", requestData)[ResponseType]}}}
    */
  def invoke[Req: JsonCodec](appId: AppId, method: String, data: Req)[Resp: JsonCodec]: Resp throws DaprServiceInvocationException

  /** Invoke a remote method with no request body (HTTP GET). */
  def invokeGet[Resp: JsonCodec](appId: AppId, method: String): Resp throws DaprServiceInvocationException

// ---------------------------------------------------------------------------

/** Capability for reading secrets from a named DAPR secrets store. */
@scala.caps.assumeSafe
trait SecretsCapability extends DaprCapability:
  val storeName: SecretStoreName

  /** Retrieve a single named secret value. Throws [[DaprSecretsException]] if absent. */
  def get(key: String): String throws DaprSecretsException

  /** Retrieve all secrets in the store as a flat key→value map. */
  def getBulk(): Map[String, String] throws DaprSecretsException

// ---------------------------------------------------------------------------

/** Capability for reading configuration items from a named DAPR config store. */
@scala.caps.assumeSafe
trait ConfigurationCapability extends DaprCapability:
  val storeName: ConfigStoreName

  /** Retrieve one or more configuration items by key. */
  def get(keys: Seq[String]): Map[String, ConfigItem] throws DaprConfigurationException

  /** Subscribe to live configuration changes for the given keys.
    *
    * `onChange` is called on a background thread whenever the sidecar delivers
    * an update.  Returns an `AutoCloseable` that stops the subscription when
    * closed.  The subscription is also stopped when the enclosing [[DaprScope]]
    * is closed.
    */
  def subscribe(keys: Seq[String])(onChange: ConfigUpdate => Unit): AutoCloseable throws DaprConfigurationException

// ---------------------------------------------------------------------------

/** Capability for invoking DAPR output bindings. */
@scala.caps.assumeSafe
trait BindingsCapability extends DaprCapability:
  val bindingName: BindingName

  /** Invoke a binding operation that may return a response.
    * `Req` is inferred from `data`; `Resp` is specified at the call site:
    * {{{binding.invoke("operation", requestData)[ResponseType]}}}
    */
  def invoke[Req: JsonCodec](operation: String, data: Req)[Resp: JsonCodec]: Option[Resp] throws DaprBindingsException

  /** Fire-and-forget binding invocation (no response expected). */
  def invokeOneWay[Req: JsonCodec](operation: String, data: Req): Unit throws DaprBindingsException

// ---------------------------------------------------------------------------

/** Capability for DAPR distributed locking against a named lock store. */
@scala.caps.assumeSafe
trait DistributedLockCapability extends DaprCapability:
  val storeName: StoreName

  /** Try to acquire a lock. Returns true if acquired, false if already held. */
  def tryLock(resourceId: String, lockOwner: String, expirySeconds: Int): Boolean throws DaprLockException

  /** Release a previously acquired lock. */
  def unlock(resourceId: String, lockOwner: String): UnlockStatus throws DaprLockException
