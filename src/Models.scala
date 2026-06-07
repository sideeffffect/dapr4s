package dapr4s

import language.experimental.safe
import scala.concurrent.duration.FiniteDuration

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
  *   The current configuration value.
  * @param version
  *   The store-assigned version token (empty string if the store does not support versioning).
  * @param metadata
  *   Additional key-value metadata attached to the item by the configuration store.
  */
final case class ConfigItem(
    key: ConfigKey,
    value: ConfigValue,
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
  final case class UpsertOp(key: StateStoreKey, encodedValue: SerializedJson, etag: Option[ETag]) extends StateOp

  object UpsertOp:
    /** Smart constructor that encodes `value` immediately using its [[JsonCodec]]. */
    def apply[T: JsonCodec](key: StateStoreKey, value: T, etag: Option[ETag] = None): UpsertOp =
      new UpsertOp(key, SerializedJson(summon[JsonCodec[T]].encode(value)), etag)

  /** Delete a key with an optional ETag for optimistic concurrency. */
  final case class DeleteOp(key: StateStoreKey, etag: Option[ETag] = None) extends StateOp

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
    methodName: InvocationMethodName,
    httpMethod: HttpMethod,
    data: T,
)

// ---------------------------------------------------------------------------
// Workflow
// ---------------------------------------------------------------------------

/** Runtime status of a Dapr workflow instance.
  *
  *   - [[WorkflowStatus.Running]] — executing normally; may be waiting for an activity, timer, or external event.
  *   - [[WorkflowStatus.Completed]] — finished successfully; output is available via
  *     [[WorkflowSnapshot.serializedOutput]].
  *   - [[WorkflowStatus.ContinuedAsNew]] — restarted with new input via [[WorkflowContext.continueAsNew]]; history
  *     cleared.
  *   - [[WorkflowStatus.Failed]] — terminated due to an unhandled exception in workflow logic.
  *   - [[WorkflowStatus.Canceled]] — cancelled by the runtime or via an explicit API call.
  *   - [[WorkflowStatus.Terminated]] — forcibly stopped via [[WorkflowCapability.terminate]].
  *   - [[WorkflowStatus.Pending]] — scheduled but not yet started (placement in progress).
  *   - [[WorkflowStatus.Suspended]] — paused via [[WorkflowCapability.suspend]]; resumes via
  *     [[WorkflowCapability.resume]].
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
  *   The [[WorkflowName]] (simple class name) that identifies the workflow type.
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

// ---------------------------------------------------------------------------
// Jobs
// ---------------------------------------------------------------------------

/** When a [[JobsCapability.schedule]] job should run.
  *
  * The Dapr scheduler accepts a cron expression, a fixed period, or one of the named shortcuts. Construct via the cases
  * directly (e.g. `JobSchedule.Cron("0 30 * * * *")`, `JobSchedule.Every(5.seconds)`, `JobSchedule.Daily`).
  */
enum JobSchedule:
  /** A standard cron expression (Dapr uses a 6-field, seconds-first format). */
  case Cron(expression: String)

  /** Run repeatedly with a fixed period between runs. */
  case Every(period: FiniteDuration)

  case Daily
  case Hourly
  case Weekly
  case Monthly
  case Yearly

/** A job's stored definition, as returned by [[JobsCapability.get]].
  *
  * @param name
  *   The job's [[JobName]].
  * @param data
  *   The job's payload as stored by the scheduler (the JSON the job was scheduled with), if any.
  * @param scheduleExpression
  *   The raw schedule expression the scheduler holds (e.g. `"@every 5s"`, `"@daily"`, or a cron string), if the job is
  *   recurring.
  * @param dueTime
  *   The one-shot due time, if the job was scheduled to run once at a specific instant.
  * @param repeats
  *   The remaining number of times the job will run, if a repeat count was set.
  * @param ttl
  *   The instant after which the job expires, if a TTL was set.
  */
final case class JobDetails(
    name: JobName,
    data: Option[SerializedJson],
    scheduleExpression: Option[String],
    dueTime: Option[java.time.Instant],
    repeats: Option[Int],
    ttl: Option[java.time.Instant],
)

// ---------------------------------------------------------------------------
// Conversation (LLM)
// ---------------------------------------------------------------------------

/** Role of a message in a [[ConversationCapability.converse]] exchange. */
enum ConversationMessageRole:
  case System, User, Assistant, Tool, Developer

/** Why the model stopped generating a [[ConversationResultChoices]].
  *
  * Providers report this as a free-form string; values outside the recognised set are preserved verbatim in
  * [[FinishReason.Other]].
  */
enum FinishReason:
  case Stop
  case Length
  case ToolCalls
  case ContentFilter
  case Other(raw: String)

object FinishReason:
  /** Map a provider's raw finish-reason string onto a [[FinishReason]]; unknown values become [[Other]]. */
  def fromWire(raw: String): FinishReason =
    raw.toLowerCase match
      case "stop"           => Stop
      case "length"         => Length
      case "tool_calls"     => ToolCalls
      case "content_filter" => ContentFilter
      case _                => Other(raw)

/** Controls whether (and which) tool the model may call in a [[ConversationCapability.converse]] request. */
enum ToolChoice:
  /** Let the model decide whether to call a tool. */
  case Auto

  /** Forbid tool calls; the model must answer directly. */
  case None

  /** Require the model to call at least one tool. */
  case Required

  /** Require the model to call the named tool. */
  case Named(name: ToolName)

object ToolChoice:
  extension (tc: ToolChoice)
    /** The string the Dapr conversation API expects for this choice. */
    def wireValue: String = tc match
      case ToolChoice.Auto        => "auto"
      case ToolChoice.None        => "none"
      case ToolChoice.Required    => "required"
      case ToolChoice.Named(name) => name.value

/** A single message in a [[ConversationCapability.converse]] request.
  *
  * Use the smart constructors ([[ConversationMessage.user]], [[ConversationMessage.system]], etc.) rather than the raw
  * apply.
  *
  * @param role
  *   Who authored the message.
  * @param text
  *   The message text.
  * @param name
  *   Optional author name (used by some providers, e.g. to attribute a tool result).
  */
final case class ConversationMessage(role: ConversationMessageRole, text: String, name: Option[String] = None)
object ConversationMessage:
  def system(text: String): ConversationMessage = ConversationMessage(ConversationMessageRole.System, text)
  def user(text: String): ConversationMessage = ConversationMessage(ConversationMessageRole.User, text)
  def assistant(text: String): ConversationMessage = ConversationMessage(ConversationMessageRole.Assistant, text)
  def tool(text: String, name: Option[String] = None): ConversationMessage =
    ConversationMessage(ConversationMessageRole.Tool, text, name)
  def developer(text: String): ConversationMessage = ConversationMessage(ConversationMessageRole.Developer, text)

/** A function/tool the model may call during a [[ConversationCapability.converse]] exchange.
  *
  * @param name
  *   The function name the model uses to invoke the tool.
  * @param description
  *   Optional human-readable description that helps the model decide when to call it.
  * @param parametersJson
  *   The function's parameter schema as a JSON object (typically a JSON Schema describing the arguments).
  */
final case class ConversationTools(name: ToolName, description: Option[String], parametersJson: SerializedJson)

/** A tool/function call the model emitted in its response. */
final case class ConversationToolCalls(id: ToolCallId, functionName: ToolName, arguments: SerializedJson)

/** The assistant message of a single [[ConversationResultChoices]]. */
final case class ConversationResultMessage(content: String, toolCalls: List[ConversationToolCalls])

/** One candidate completion within a [[ConversationResult]]. */
final case class ConversationResultChoices(
    finishReason: Option[FinishReason],
    index: Long,
    message: ConversationResultMessage,
)

/** Token usage reported by the model for a [[ConversationResult]], when the provider supplies it. */
final case class ConversationResultCompletionUsage(
    promptTokens: Option[Long],
    completionTokens: Option[Long],
    totalTokens: Option[Long],
)

/** One output of a [[ConversationResponse]] (one per conversation input). */
final case class ConversationResult(
    choices: List[ConversationResultChoices],
    model: Option[ModelName],
    usage: Option[ConversationResultCompletionUsage],
)

/** The full response of a [[ConversationCapability.converse]] call. */
final case class ConversationResponse(
    contextId: Option[ConversationContextId],
    outputs: List[ConversationResult],
)
