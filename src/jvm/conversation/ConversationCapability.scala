//> using target.platform "jvm"
package dapr4s.conversation

import dapr4s.*

/** Universal capability for invoking DAPR conversation (LLM) components: reaches **any** component, so every operation
  * names its component.
  *
  * Obtained via [[DaprCapability.conversation]]. Narrow to one component with [[instance]]
  * (`conversation.instance(componentName)`), which yields a [[ConversationCapability.Instance]].
  *
  * '''JVM-only:''' the Dapr JS SDK has no conversation API, so this capability (and [[DaprCapability.conversation]],
  * via `DaprCapabilityPlatform`) exists only on the JVM — on Scala.js using it is a compile error.
  *
  * `converse` holds a multi-message exchange — message roles, optional tool/function calling, and usage reporting.
  */
@scala.caps.assumeSafe
trait ConversationCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to the named conversation (LLM) component. */
  def instance(componentName: ConversationComponentName): ConversationCapability.Instance^{this}

  /** Hold a multi-message exchange with the named component; see [[ConversationCapability.Instance.converse]]. */
  def converse(
      componentName: ConversationComponentName,
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTool] = Nil,
      toolChoice: Option[ToolChoice] = None,
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  ): ConversationResponse

/** The [[ConversationCapability]] family: the component-bound [[ConversationCapability.Instance]] and the
  * companion-object forwarders for both interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def ask(prompt: String)(using ConversationCapability.Instance): ConversationResponse =
  *     ConversationCapability.Instance.converse(Seq(ConversationMessage.user(prompt)))
  * }}}
  */
@scala.caps.assumeSafe
object ConversationCapability:

  /** Capability bound to one named conversation (LLM) component. Obtained via `conversation.instance(componentName)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The conversation component this capability is bound to. */
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

  /** Forwarders for [[ConversationCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def converse(
        messages: Seq[ConversationMessage],
        tools: Seq[ConversationTool] = Nil,
        toolChoice: Option[ToolChoice] = None,
        temperature: Option[Double] = None,
        contextId: Option[ConversationContextId] = None,
        scrubPii: Boolean = false,
    )(using cap: Instance): ConversationResponse =
      cap.converse(messages, tools, toolChoice, temperature, contextId, scrubPii)

  /** Narrow the universal capability in scope to one component and return it. */
  def instance(componentName: ConversationComponentName)(using cap: ConversationCapability): Instance^{cap} =
    cap.instance(componentName)
  def converse(
      componentName: ConversationComponentName,
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTool] = Nil,
      toolChoice: Option[ToolChoice] = None,
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  )(using cap: ConversationCapability): ConversationResponse =
    cap.converse(componentName, messages, tools, toolChoice, temperature, contextId, scrubPii)
