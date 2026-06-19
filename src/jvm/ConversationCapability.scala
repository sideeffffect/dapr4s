//> using target.platform "jvm"
package dapr4s

/** Capability for invoking a DAPR conversation (LLM) component.
  *
  * '''JVM-only:''' the Dapr JS SDK has no conversation API, so this capability (and [[DaprCapability.conversation]],
  * via `DaprCapabilityPlatform`) exists only on the JVM — on Scala.js using it is a compile error.
  *
  * [[converse]] holds a multi-message exchange — message roles, optional tool/function calling, and usage reporting.
  * Acquired via [[DaprCapability.conversation]].
  */
/** Accessor (rung 2) for conversation components: an "any component" handle obtained argument-less via
  * [[DaprCapability.conversation]], whose [[apply]] narrows to a [[ConversationCapability]] bound to one component.
  */
@scala.caps.assumeSafe
trait AccessConversationCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[ConversationCapability]] for the named conversation (LLM) component. */
  def apply(componentName: ConversationComponentName): ConversationCapability^{this}

@scala.caps.assumeSafe
trait ConversationCapability extends scala.caps.ExclusiveCapability:
  val componentName: ConversationComponentName

  /** Hold a multi-message exchange with optional tool definitions. */
  def converse(
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTool] = Nil,
      toolChoice: Option[ToolChoice] = None,
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  ): ConversationResponse

/** Companion-object API for [[ConversationCapability]].
  *
  * Forwards to the `ConversationCapability` in the enclosing `using` context:
  * {{{
  *   def ask(prompt: String)(using ConversationCapability): ConversationResponse =
  *     ConversationCapability.converse(Seq(ConversationMessage.user(prompt)))
  * }}}
  */
@scala.caps.assumeSafe
object ConversationCapability:
  def converse(
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTool] = Nil,
      toolChoice: Option[ToolChoice] = None,
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  )(using cap: ConversationCapability): ConversationResponse =
    cap.converse(messages, tools, toolChoice, temperature, contextId, scrubPii)
