package dapr.safe

import language.experimental.safe

// ---------------------------------------------------------------------------
// Opaque domain types — prevent accidental misuse (e.g. StoreName vs PubSubName)
// ---------------------------------------------------------------------------

opaque type StoreName = String
object StoreName:
  def apply(s: String): StoreName =
    require(s.nonEmpty, "StoreName must not be empty")
    s
  extension (n: StoreName) def value: String = n

opaque type PubSubName = String
object PubSubName:
  def apply(s: String): PubSubName =
    require(s.nonEmpty, "PubSubName must not be empty")
    s
  extension (n: PubSubName) def value: String = n

opaque type Topic = String
object Topic:
  def apply(s: String): Topic =
    require(s.nonEmpty, "Topic must not be empty")
    s
  extension (n: Topic) def value: String = n

opaque type AppId = String
object AppId:
  def apply(s: String): AppId =
    require(s.nonEmpty, "AppId must not be empty")
    s
  extension (n: AppId) def value: String = n

opaque type SecretStoreName = String
object SecretStoreName:
  def apply(s: String): SecretStoreName =
    require(s.nonEmpty, "SecretStoreName must not be empty")
    s
  extension (n: SecretStoreName) def value: String = n

opaque type ConfigStoreName = String
object ConfigStoreName:
  def apply(s: String): ConfigStoreName =
    require(s.nonEmpty, "ConfigStoreName must not be empty")
    s
  extension (n: ConfigStoreName) def value: String = n

opaque type BindingName = String
object BindingName:
  def apply(s: String): BindingName =
    require(s.nonEmpty, "BindingName must not be empty")
    s
  extension (n: BindingName) def value: String = n

opaque type ETag = String
object ETag:
  def apply(s: String): ETag = s
  extension (n: ETag) def value: String = n

opaque type StateQuery = String
object StateQuery:
  def apply(query: String): StateQuery = query
  extension (s: StateQuery) def value: String = s

opaque type StateKey = String
object StateKey:
  def apply(s: String): StateKey = s
  extension (k: StateKey) def value: String = k

opaque type MethodName = String
object MethodName:
  def apply(s: String): MethodName =
    require(s.nonEmpty, "MethodName must not be empty")
    s
  extension (n: MethodName) def value: String = n

opaque type BindingOperation = String
object BindingOperation:
  def apply(s: String): BindingOperation =
    require(s.nonEmpty, "BindingOperation must not be empty")
    s
  extension (n: BindingOperation) def value: String = n

opaque type SecretKey = String
object SecretKey:
  def apply(s: String): SecretKey = s
  extension (k: SecretKey) def value: String = k

opaque type ConfigKey = String
object ConfigKey:
  def apply(s: String): ConfigKey = s
  extension (k: ConfigKey) def value: String = k

opaque type LockResourceId = String
object LockResourceId:
  def apply(s: String): LockResourceId =
    require(s.nonEmpty, "LockResourceId must not be empty")
    s
  extension (id: LockResourceId) def value: String = id

opaque type LockOwner = String
object LockOwner:
  def apply(s: String): LockOwner =
    require(s.nonEmpty, "LockOwner must not be empty")
    s
  extension (o: LockOwner) def value: String = o

opaque type BulkEntryId = String
object BulkEntryId:
  def apply(s: String): BulkEntryId = s
  extension (id: BulkEntryId) def value: String = id

opaque type Route = String
object Route:
  def apply(s: String): Route =
    require(s.nonEmpty, "Route must not be empty")
    s
  extension (r: Route) def value: String = r

opaque type ActorType = String
object ActorType:
  def apply(s: String): ActorType =
    require(s.nonEmpty, "ActorType must not be empty")
    s
  extension (t: ActorType) def value: String = t

opaque type ActorId = String
object ActorId:
  def apply(s: String): ActorId = s
  extension (id: ActorId) def value: String = id

opaque type ReminderName = String
object ReminderName:
  def apply(s: String): ReminderName =
    require(s.nonEmpty, "ReminderName must not be empty")
    s
  extension (n: ReminderName) def value: String = n

opaque type TimerName = String
object TimerName:
  def apply(s: String): TimerName =
    require(s.nonEmpty, "TimerName must not be empty")
    s
  extension (n: TimerName) def value: String = n

opaque type WorkflowName = String
object WorkflowName:
  def apply(s: String): WorkflowName =
    require(s.nonEmpty, "WorkflowName must not be empty")
    s
  extension (n: WorkflowName) def value: String = n

opaque type WorkflowInstanceId = String
object WorkflowInstanceId:
  def apply(s: String): WorkflowInstanceId = s
  extension (id: WorkflowInstanceId) def value: String = id

/** Standard HTTP methods for service invocation requests. */
enum HttpMethod:
  case Get, Post, Put, Patch, Delete, Head, Options

// ---------------------------------------------------------------------------
// Value types
// ---------------------------------------------------------------------------

/** Result of a state fetch that also exposes the server-side ETag. */
final case class StateEntry[T](value: Option[T], etag: Option[ETag])

/** A single item returned by the configuration API. */
final case class ConfigItem(
    key: ConfigKey,
    value: String,
    version: String,
    metadata: Map[String, String] = Map.empty,
)

// ---------------------------------------------------------------------------
// State transaction operations
// ---------------------------------------------------------------------------

sealed abstract class StateOp

object StateOp:
  /** Upsert a key with a pre-encoded JSON value and an optional ETag.
    *
    * Values are encoded at construction time to avoid type erasure issues when the operation is processed in
    * [[StateCapability.transaction]]. Use the companion `apply[T]` smart constructor to encode a typed value.
    */
  final case class UpsertOp(key: StateKey, encodedValue: String, etag: Option[ETag]) extends StateOp

  object UpsertOp:
    /** Smart constructor that encodes `value` immediately using its [[JsonCodec]]. */
    def apply[T: JsonCodec](key: StateKey, value: T, etag: Option[ETag] = None): UpsertOp =
      new UpsertOp(key, summon[JsonCodec[T]].encode(value), etag)

  /** Delete a key with an optional ETag for optimistic concurrency. */
  final case class DeleteOp(key: StateKey, etag: Option[ETag] = None) extends StateOp

// ---------------------------------------------------------------------------
// Distributed Lock
// ---------------------------------------------------------------------------

/** Result status of an unlock operation. */
enum UnlockStatus:
  case Success
  case LockNotFound
  case InternalError

// ---------------------------------------------------------------------------
// Bulk Pub/Sub
// ---------------------------------------------------------------------------

/** An entry in a bulk publish request. */
final case class BulkPublishEntry[T](entryId: BulkEntryId, event: T)

/** Result of a bulk publish — contains IDs of any failed entries. */
final case class BulkPublishResult(failedEntries: List[BulkEntryId])

// ---------------------------------------------------------------------------
// Configuration subscription
// ---------------------------------------------------------------------------

/** Represents a configuration update notification. */
final case class ConfigUpdate(storeName: ConfigStoreName, items: Map[ConfigKey, ConfigItem])

// ---------------------------------------------------------------------------
// Pub/Sub subscription (incoming messages)
// ---------------------------------------------------------------------------

/** What a subscription handler should do with the received message. */
enum SubscriptionResult:
  /** ACK — do not redeliver. */
  case Success

  /** NAK — redeliver after the configured retry interval. */
  case Retry

  /** Silently discard — do not redeliver, do not report an error. */
  case Drop

/** An incoming pub/sub CloudEvent delivered by the Dapr sidecar. */
final case class CloudEvent[T](
    id: String,
    source: String,
    specVersion: String,
    eventType: String,
    topic: Topic,
    pubSubName: PubSubName,
    dataContentType: String,
    data: T,
)

// ---------------------------------------------------------------------------
// Service invocation (as a target)
// ---------------------------------------------------------------------------

/** An incoming service invocation request. `httpMethod` is the HTTP verb (GET, POST, PUT, DELETE, etc.) used by the
  * calling app.
  */
final case class InvocationRequest[T](
    methodName: MethodName,
    httpMethod: HttpMethod,
    data: T,
)

// ---------------------------------------------------------------------------
// Workflow
// ---------------------------------------------------------------------------

/** Runtime status of a Dapr workflow instance. */
enum WorkflowStatus:
  case Running
  case Completed
  case ContinuedAsNew
  case Failed
  case Canceled
  case Terminated
  case Pending
  case Suspended

/** A snapshot of a workflow instance's current state. */
final case class WorkflowSnapshot(
    name: WorkflowName,
    instanceId: WorkflowInstanceId,
    status: WorkflowStatus,
    createdAt: java.time.Instant,
    lastUpdatedAt: java.time.Instant,
    serializedInput: Option[String],
    serializedOutput: Option[String],
)
