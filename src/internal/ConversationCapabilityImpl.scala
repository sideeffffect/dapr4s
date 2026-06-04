package dapr4s.internal

import dapr4s.*
import com.fasterxml.jackson.databind.ObjectMapper
import io.dapr.client.domain.{
  ConversationInput,
  ConversationInputAlpha2,
  ConversationMessage as JConversationMessage,
  ConversationMessageContent,
  ConversationMessageRole as JConversationMessageRole,
  ConversationRequest,
  ConversationRequestAlpha2,
  ConversationResponseAlpha2 as JConversationResponseAlpha2,
  ConversationResultAlpha2 as JConversationResultAlpha2,
  ConversationTools as JConversationTools,
  ConversationToolsFunction,
}
import scala.jdk.CollectionConverters.*
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[dapr4s] final class ConversationCapabilityImpl(
    scope: DaprCapabilityImpl,
    val componentName: ConversationComponentName,
) extends ConversationCapability:

  import ConversationCapabilityImpl.*

  // The SDK marks the alpha1 `converse` API deprecated in favour of alpha2, but we deliberately
  // expose both (see ConversationCapability.converseMany vs converseAlpha2), so silence the deprecation here.
  @annotation.nowarn("cat=deprecation")
  def converseMany(
      prompts: Seq[String],
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  ): List[String] =
    val inputs = prompts.map { p =>
      val in = new ConversationInput(p)
      in.setScrubPii(scrubPii)
      in
    }.asJava
    val req = new ConversationRequest(componentName.value, inputs)
    req.setScrubPii(scrubPii)
    contextId.foreach(c => req.setContextId(c.value))
    temperature.foreach(t => req.setTemperature(t))
    val resp = scope.clientPreview.converse(req).awaitResult()
    resp.toOption
      .flatMap(r => Option(r.getConversationOutputs))
      .fold(List.empty[String])(_.asScala.toList.map(o => o.getResult.nn))

  def converseAlpha2(
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTools] = Nil,
      toolChoice: Option[ToolChoice] = None,
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  ): ConversationResponseAlpha2 =
    val input = new ConversationInputAlpha2(messages.map(toJavaMessage).asJava)
    input.setScrubPii(scrubPii)
    val req = new ConversationRequestAlpha2(componentName.value, java.util.List.of(input))
    req.setScrubPii(scrubPii)
    contextId.foreach(c => req.setContextId(c.value))
    temperature.foreach(t => req.setTemperature(t))
    if tools.nonEmpty then req.setTools(tools.map(toJavaTool).asJava)
    toolChoice.foreach(tc => req.setToolChoice(tc.wireValue))
    val resp = scope.clientPreview.converseAlpha2(req).awaitResult()
    toResponse(resp)

@scala.caps.assumeSafe
private object ConversationCapabilityImpl:
  private val mapper = new ObjectMapper()

  def toResponse(resp: JConversationResponseAlpha2 | Null): ConversationResponseAlpha2 =
    resp.toOption.fold(ConversationResponseAlpha2(None, Nil)) { r =>
      val outputs =
        Option(r.getOutputs).fold(List.empty[ConversationResultAlpha2])(_.asScala.toList.map(toResult))
      ConversationResponseAlpha2(Option(r.getContextId).map(ConversationContextId(_)), outputs)
    }

  def toResult(out: JConversationResultAlpha2): ConversationResultAlpha2 =
    val choices = Option(out.getChoices).fold(List.empty[ConversationResultChoices]) {
      _.asScala.toList.map { c =>
        val msg = c.getMessage.nn
        val toolCalls = Option(msg.getToolCalls).fold(List.empty[ConversationToolCalls]) {
          _.asScala.toList.map { tc =>
            val fn = tc.getFunction.nn
            ConversationToolCalls(ToolCallId(tc.getId.nn), ToolName(fn.getName.nn), SerializedJson(fn.getArguments.nn))
          }
        }
        ConversationResultChoices(
          Option(c.getFinishReason).map(FinishReason.fromWire),
          c.getIndex,
          ConversationResultMessage(msg.getContent.nn, toolCalls),
        )
      }
    }
    val usage = Option(out.getUsage).map { u =>
      ConversationResultCompletionUsage(Some(u.getPromptTokens), Some(u.getCompletionTokens), Some(u.getTotalTokens))
    }
    ConversationResultAlpha2(choices, Option(out.getModel).map(ModelName(_)), usage)

  def toJavaMessage(m: ConversationMessage): JConversationMessage =
    val role = m.role match
      case ConversationMessageRole.System    => JConversationMessageRole.SYSTEM
      case ConversationMessageRole.User      => JConversationMessageRole.USER
      case ConversationMessageRole.Assistant => JConversationMessageRole.ASSISTANT
      case ConversationMessageRole.Tool      => JConversationMessageRole.TOOL
      case ConversationMessageRole.Developer => JConversationMessageRole.DEVELOPER
    val contents = java.util.List.of(new ConversationMessageContent(m.text))
    new SimpleMessage(role, m.name.orNull, contents)

  def toJavaTool(t: ConversationTools): JConversationTools =
    val params = mapper
      .readValue(t.parametersJson.value, classOf[java.util.Map[?, ?]])
      .asInstanceOf[java.util.Map[String, Object]]
    val fn = new ConversationToolsFunction(t.name.value, params)
    t.description.foreach(fn.setDescription)
    new JConversationTools(fn)

  /** Minimal [[JConversationMessage]] implementation; the SDK ships only the interface. */
  private final class SimpleMessage(
      r: JConversationMessageRole,
      n: String | Null,
      c: java.util.List[ConversationMessageContent],
  ) extends JConversationMessage:
    override def getRole(): JConversationMessageRole = r
    override def getName(): String | Null = n
    override def getContent(): java.util.List[ConversationMessageContent] = c
