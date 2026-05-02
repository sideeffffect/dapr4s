package dapr.safe

import scala.caps.Capability

// ---------------------------------------------------------------------------
// Root capability marker — all DAPR capabilities extend this
// ---------------------------------------------------------------------------

/** Marker trait for all DAPR capabilities. Extends [[scala.caps.Capability]]
  * so that the Scala 3 capture checker can track usages.
  */
sealed trait DaprCapability extends Capability

// ---------------------------------------------------------------------------
// Individual capability traits
// ---------------------------------------------------------------------------

/** Capability for DAPR state management operations against a named store.
  *
  * Acquired via [[DaprScope.state]].
  */
trait StateCapability extends DaprCapability:
  val storeName: StoreName

  /** Fetch a value; returns `None` if the key does not exist. */
  def get[T: JsonCodec](key: String): Option[T]

  /** Fetch a value together with the current server-side ETag. */
  def getWithETag[T: JsonCodec](key: String): StateEntry[T]

  /** Unconditionally save a value. */
  def save[T: JsonCodec](key: String, value: T): Unit

  /** Save a value only if the provided ETag matches the server-side ETag.
    * Throws [[ETagMismatchException]] on conflict.
    */
  def saveWithETag[T: JsonCodec](key: String, value: T, etag: ETag): Unit

  /** Unconditionally delete a key (no-op if the key is absent). */
  def delete(key: String): Unit

  /** Delete a key only if the provided ETag matches.
    * Throws [[ETagMismatchException]] on conflict.
    */
  def deleteWithETag(key: String, etag: ETag): Unit

  /** Execute multiple state operations atomically (all-or-nothing). */
  def transaction(ops: Seq[StateOp]): Unit

// ---------------------------------------------------------------------------

/** Capability for DAPR pub/sub publish operations against a named component. */
trait PubSubCapability extends DaprCapability:
  val pubsubName: PubSubName

  /** Publish `data` to `topic`. */
  def publish[T: JsonCodec](topic: Topic, data: T): Unit

  /** Publish `data` to `topic` with additional metadata headers. */
  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  ): Unit

// ---------------------------------------------------------------------------

/** Capability for synchronous service invocation (RPC) via DAPR. */
trait ServiceInvocationCapability extends DaprCapability:

  /** Invoke a remote method with a request body (HTTP POST). */
  def invoke[Req: JsonCodec, Resp: JsonCodec](
      appId: AppId,
      method: String,
      data: Req
  ): Resp

  /** Invoke a remote method with no request body (HTTP GET). */
  def invokeGet[Resp: JsonCodec](appId: AppId, method: String): Resp

// ---------------------------------------------------------------------------

/** Capability for reading secrets from a named DAPR secrets store. */
trait SecretsCapability extends DaprCapability:
  val storeName: SecretStoreName

  /** Retrieve a single named secret value. Throws [[DaprException]] if absent. */
  def get(key: String): String

  /** Retrieve all secrets in the store as a flat key→value map. */
  def getBulk(): Map[String, String]

// ---------------------------------------------------------------------------

/** Capability for reading configuration items from a named DAPR config store. */
trait ConfigurationCapability extends DaprCapability:
  val storeName: ConfigStoreName

  /** Retrieve one or more configuration items by key. */
  def get(keys: String*): Map[String, ConfigItem]

// ---------------------------------------------------------------------------

/** Capability for invoking DAPR output bindings. */
trait BindingsCapability extends DaprCapability:
  val bindingName: BindingName

  /** Invoke a binding operation that may return a response. */
  def invoke[Req: JsonCodec, Resp: JsonCodec](
      operation: String,
      data: Req
  ): Option[Resp]

  /** Fire-and-forget binding invocation (no response expected). */
  def invokeOneWay[Req: JsonCodec](operation: String, data: Req): Unit
