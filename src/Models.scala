package dapr4s

import language.experimental.safe

/** Standard HTTP methods for service invocation requests. */
enum HttpMethod:
  case Get, Post, Put, Patch, Delete, Head, Options

// ---------------------------------------------------------------------------
// Value types
// ---------------------------------------------------------------------------

/** Result of a state fetch that also exposes the server-side ETag. */
final case class StateEntry[T](value: Option[T], etag: Option[ETag])

/** A single configuration item returned by [[ConfigurationCapability.get]] or delivered via subscription.
  *
  * @param key
  *   The [[ConfigKey]] identifying this item.
  * @param value
  *   The current configuration value as a string.
  * @param version
  *   The store-assigned version token (empty string if the store does not support versioning).
  * @param metadata
  *   Additional key-value metadata attached to the item by the configuration store.
  */
final case class ConfigItem(
    key: ConfigKey,
    value: String,
    version: ConfigVersion,
    metadata: Map[MetadataKey, MetadataValue] = Map.empty,
)

// ---------------------------------------------------------------------------
// State transaction operations
// ---------------------------------------------------------------------------

/** Base type for operations in a [[StateCapability.transaction]] call.
  *
  * All-or-nothing: if any operation fails the transaction the entire batch is rolled back. Use the smart constructors
  * [[StateOp.UpsertOp]] and [[StateOp.DeleteOp]].
  */
sealed abstract class StateOp

object StateOp:
  /** Upsert a key with a pre-encoded JSON value and an optional ETag.
    *
    * Values are encoded at construction time to avoid type erasure issues when the operation is processed in
    * [[StateCapability.transaction]]. Use the companion `apply[T]` smart constructor to encode a typed value.
    */
  final case class UpsertOp(key: StateKey, encodedValue: SerializedJson, etag: Option[ETag]) extends StateOp

  object UpsertOp:
    /** Smart constructor that encodes `value` immediately using its [[JsonCodec]]. */
    def apply[T: JsonCodec](key: StateKey, value: T, etag: Option[ETag] = None): UpsertOp =
      new UpsertOp(key, SerializedJson(summon[JsonCodec[T]].encode(value)), etag)

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

/** A CloudEvent envelope wrapping an inbound pub/sub message delivered by the Dapr sidecar.
  *
  * The sidecar deserialises the raw message from the broker into this structure before calling the subscription
  * handler. `data` is the typed payload; all other fields come from the CloudEvents envelope.
  *
  * @param id
  *   Unique event identifier (UUID).
  * @param source
  *   URI-reference identifying the event producer (e.g. `"/orders/service"`).
  * @param specVersion
  *   CloudEvents specification version (e.g. `"1.0"`).
  * @param eventType
  *   Reverse-DNS event type (e.g. `"com.example.OrderCreated"`).
  * @param topic
  *   The pub/sub topic on which the event arrived.
  * @param pubSubName
  *   The Dapr pub/sub component that delivered the event.
  * @param dataContentType
  *   MIME type of the raw payload (e.g. `"application/json"`).
  * @param data
  *   The deserialised event payload.
  */
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

/** Runtime status of a Dapr workflow instance.
  *
  *   - [[WorkflowStatus.Running]] — executing normally; may be waiting for an activity, timer, or external event.
  *   - [[WorkflowStatus.Completed]] — finished successfully; output is available via [[WorkflowSnapshot.serializedOutput]].
  *   - [[WorkflowStatus.ContinuedAsNew]] — restarted with new input via [[WorkflowContext.continueAsNew]]; history cleared.
  *   - [[WorkflowStatus.Failed]] — terminated due to an unhandled exception in workflow logic.
  *   - [[WorkflowStatus.Canceled]] — cancelled by the runtime or via an explicit API call.
  *   - [[WorkflowStatus.Terminated]] — forcibly stopped via [[WorkflowCapability.terminate]].
  *   - [[WorkflowStatus.Pending]] — scheduled but not yet started (placement in progress).
  *   - [[WorkflowStatus.Suspended]] — paused via [[WorkflowCapability.suspend]]; resumes via [[WorkflowCapability.resume]].
  */
enum WorkflowStatus:
  case Running
  case Completed
  case ContinuedAsNew
  case Failed
  case Canceled
  case Terminated
  case Pending
  case Suspended

/** A point-in-time snapshot of a workflow instance's state.
  *
  * Returned by [[WorkflowCapability.getStatus]] and [[WorkflowCapability.waitForCompletion]].
  *
  * @param name
  *   The [[WorkflowName]] (canonical class name) that identifies the workflow type.
  * @param instanceId
  *   The unique [[WorkflowInstanceId]] of this instance.
  * @param status
  *   Current [[WorkflowStatus]] of the instance.
  * @param createdAt
  *   When the instance was created (UTC).
  * @param lastUpdatedAt
  *   When the instance last changed state (UTC).
  * @param serializedInput
  *   The JSON-encoded workflow input, if one was provided at start.
  * @param serializedOutput
  *   The JSON-encoded workflow output set by [[WorkflowContext.complete]], if completed.
  */
final case class WorkflowSnapshot(
    name: WorkflowName,
    instanceId: WorkflowInstanceId,
    status: WorkflowStatus,
    createdAt: java.time.Instant,
    lastUpdatedAt: java.time.Instant,
    serializedInput: Option[SerializedJson],
    serializedOutput: Option[SerializedJson],
)
