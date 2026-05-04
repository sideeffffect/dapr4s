package dapr.safe

import language.experimental.safe

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
    id: CloudEventId,
    source: CloudEventSource,
    specVersion: CloudEventSpecVersion,
    eventType: CloudEventType,
    topic: Topic,
    pubSubName: PubSubName,
    dataContentType: ContentType,
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
