//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import io.dapr.client.domain.{
  ConversationInputAlpha2,
  ConversationMessage as JConversationMessage,
  ConversationMessageContent,
  ConversationMessageRole as JConversationMessageRole,
  ConversationRequestAlpha2,
  ConversationResponseAlpha2 as JConversationResponse,
  ConversationResultAlpha2 as JConversationResult,
  ConversationTools as JConversationTools,
  ConversationToolsFunction,
}
import scala.jdk.CollectionConverters.*
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[internal] final class ConversationCapabilityImpl(
    scope: DaprCapabilityImpl,
    val componentName: ConversationComponentName,
) extends ConversationCapability:

  import ConversationCapabilityImpl.*

  def converse(
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTool] = Nil,
      toolChoice: Option[ToolChoice] = None,
      temperature: Option[Double] = None,
      contextId: Option[ConversationContextId] = None,
      scrubPii: Boolean = false,
  ): ConversationResponse =
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

  private def toResponse(resp: JConversationResponse | Null): ConversationResponse =
    resp.toOption.fold(ConversationResponse(None, Nil)) { r =>
      val outputs =
        Option(r.getOutputs).fold(List.empty[ConversationResult])(_.asScala.toList.map(toResult))
      ConversationResponse(Option(r.getContextId).map(ConversationContextId(_)), outputs)
    }

  private def toResult(out: JConversationResult): ConversationResult =
    val choices = Option(out.getChoices).fold(List.empty[ConversationResultChoice]) {
      _.asScala.toList.map { c =>
        val msg = c.getMessage.nn
        val toolCalls = Option(msg.getToolCalls).fold(List.empty[ConversationToolCall]) {
          _.asScala.toList.map { tc =>
            val fn = tc.getFunction.nn
            ConversationToolCall(ToolCallId(tc.getId.nn), ToolName(fn.getName.nn), SerializedJson(fn.getArguments.nn))
          }
        }
        ConversationResultChoice(
          Option(c.getFinishReason).map(FinishReason.fromWire),
          c.getIndex,
          ConversationResultMessage(msg.getContent.nn, toolCalls),
        )
      }
    }
    val usage = Option(out.getUsage).map { u =>
      ConversationResultCompletionUsage(Some(u.getPromptTokens), Some(u.getCompletionTokens), Some(u.getTotalTokens))
    }
    ConversationResult(choices, Option(out.getModel).map(ModelName(_)), usage)

  private def toJavaMessage(m: ConversationMessage): JConversationMessage =
    val role = m.role match
      case ConversationMessageRole.System    => JConversationMessageRole.SYSTEM
      case ConversationMessageRole.User      => JConversationMessageRole.USER
      case ConversationMessageRole.Assistant => JConversationMessageRole.ASSISTANT
      case ConversationMessageRole.Tool      => JConversationMessageRole.TOOL
      case ConversationMessageRole.Developer => JConversationMessageRole.DEVELOPER
    val contents = java.util.List.of(new ConversationMessageContent(m.text))
    new SimpleMessage(role, m.name.orNull, contents)

  private def toJavaTool(t: ConversationTool): JConversationTools =
    val params = Json.mapper
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
