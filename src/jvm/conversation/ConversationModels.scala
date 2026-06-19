//> using target.platform "jvm"
package dapr4s.conversation

import dapr4s.*

// WHAT: no `import language.experimental.safe` here, although the file these models were split
// out of (src/shared/Models.scala) is in safe mode.
// WHY: with the safe import in this jvm-tagged file, the 3.10.0-RC1 nightly's capture checker
// fails on *unrelated* files — the `@scala.caps.assumeSafe` enums in src/shared/optypes
// (StateConcurrency, StateConsistency) error on their synthesized `values` method
// ("dapr4s.X.$values.clone(): fresh cannot flow into capture set {}"). Empirically bisected to
// exactly this file's safe import (see JobsModels.scala for the full account).
// WHY SAFE: safe mode only adds checking; these are pure data definitions (enums/case classes)
// with no capabilities, no escape hatches, and no side effects — there is nothing for safe mode
// to catch here.
// WHERE TO LOOK: src/jvm/JobsModels.scala (same workaround, full explanation); AGENTS.md
// "Escape hatches" section.

// JVM-only: these models belong to the JVM-only ConversationCapability (the Dapr JS SDK has no
// conversation API — see DaprCapabilityPlatform). The conversation-related opaque types
// (ConversationComponentName, ConversationContextId, ModelName, ToolName, ToolCallId) stay
// shared in src/shared/optypes/.

/** Role of a message in a [[ConversationCapability.converse]] exchange. */
enum ConversationMessageRole:
  case System, User, Assistant, Tool, Developer

/** Why the model stopped generating a [[ConversationResultChoice]].
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
// @assumeSafe: this file is not compiled in safe mode (see the header), so these explicitly
// declared factory `def`s are otherwise unreferable from safe consumer code (the dapr4s-examples
// conversation demo constructs messages from a safe PureModule). The factories are pure — they
// only wrap a role + text in a case class — so erasing their (empty) capture set is sound.
@scala.caps.assumeSafe
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
final case class ConversationTool(name: ToolName, description: Option[String], parametersJson: SerializedJson)

/** A tool/function call the model emitted in its response. */
final case class ConversationToolCall(id: ToolCallId, functionName: ToolName, arguments: SerializedJson)

/** The assistant message of a single [[ConversationResultChoice]]. */
final case class ConversationResultMessage(content: String, toolCalls: List[ConversationToolCall])

/** One candidate completion within a [[ConversationResult]]. */
final case class ConversationResultChoice(
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
    choices: List[ConversationResultChoice],
    model: Option[ModelName],
    usage: Option[ConversationResultCompletionUsage],
)

/** The full response of a [[ConversationCapability.converse]] call. */
final case class ConversationResponse(
    contextId: Option[ConversationContextId],
    outputs: List[ConversationResult],
)
