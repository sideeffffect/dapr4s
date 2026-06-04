package dapr4s.internal

import dapr4s.*
import com.fasterxml.jackson.databind.ObjectMapper
import io.dapr.client.domain.{
  ConversationInput,
  ConversationInputAlpha2,
  ConversationMessage,
  ConversationMessageContent,
  ConversationMessageRole,
  ConversationRequest,
  ConversationRequestAlpha2,
  ConversationResponseAlpha2,
  ConversationResultAlpha2,
  ConversationTools,
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

  private val mapper = new ObjectMapper()

  // The SDK marks the alpha1 `converse` API deprecated in favour of alpha2, but we deliberately
  // expose both (see ConversationCapability.converseMany vs chat), so silence the deprecation here.
  @annotation.nowarn("cat=deprecation")
  def converseMany(
      prompts: Seq[String],
      temperature: Option[Double] = None,
      contextId: Option[String] = None,
      scrubPii: Boolean = false,
  ): List[String] =
    val inputs = prompts.map { p =>
      val in = new ConversationInput(p)
      in.setScrubPii(scrubPii)
      in
    }.asJava
    val req = new ConversationRequest(componentName.value, inputs)
    req.setScrubPii(scrubPii)
    contextId.foreach(req.setContextId)
    temperature.foreach(t => req.setTemperature(t))
    val resp = scope.clientPreview.converse(req).awaitResult()
    resp.toOption
      .flatMap(r => Option(r.getConversationOutputs))
      .fold(List.empty[String])(_.asScala.toList.map(o => o.getResult.nn))

  def chat(
      messages: Seq[ChatMessage],
      tools: Seq[ChatTool] = Nil,
      toolChoice: Option[String] = None,
      temperature: Option[Double] = None,
      contextId: Option[String] = None,
      scrubPii: Boolean = false,
  ): ChatResponse =
    val input = new ConversationInputAlpha2(messages.map(toJavaMessage).asJava)
    input.setScrubPii(scrubPii)
    val req = new ConversationRequestAlpha2(componentName.value, java.util.List.of(input))
    req.setScrubPii(scrubPii)
    contextId.foreach(req.setContextId)
    temperature.foreach(t => req.setTemperature(t))
    if tools.nonEmpty then req.setTools(tools.map(toJavaTool).asJava)
    toolChoice.foreach(req.setToolChoice)
    val resp = scope.clientPreview.converseAlpha2(req).awaitResult()
    toChatResponse(resp)

  private def toChatResponse(resp: ConversationResponseAlpha2 | Null): ChatResponse =
    resp.toOption.fold(ChatResponse(None, Nil)) { r =>
      val results = Option(r.getOutputs).fold(List.empty[ChatResult])(_.asScala.toList.map(toChatResult))
      ChatResponse(Option(r.getContextId), results)
    }

  private def toChatResult(out: ConversationResultAlpha2): ChatResult =
    val choices = Option(out.getChoices).fold(List.empty[ChatChoice]) {
      _.asScala.toList.map { c =>
        val msg = c.getMessage.nn
        val toolCalls = Option(msg.getToolCalls).fold(List.empty[ChatToolCall]) {
          _.asScala.toList.map { tc =>
            val fn = tc.getFunction.nn
            ChatToolCall(tc.getId.nn, fn.getName.nn, fn.getArguments.nn)
          }
        }
        ChatChoice(Option(c.getFinishReason), c.getIndex, ChatResultMessage(msg.getContent.nn, toolCalls))
      }
    }
    val usage = Option(out.getUsage).map { u =>
      ChatUsage(Some(u.getPromptTokens), Some(u.getCompletionTokens), Some(u.getTotalTokens))
    }
    ChatResult(choices, Option(out.getModel), usage)

  private def toJavaMessage(m: ChatMessage): ConversationMessage =
    val role = m.role match
      case ChatRole.System    => ConversationMessageRole.SYSTEM
      case ChatRole.User      => ConversationMessageRole.USER
      case ChatRole.Assistant => ConversationMessageRole.ASSISTANT
      case ChatRole.Tool      => ConversationMessageRole.TOOL
      case ChatRole.Developer => ConversationMessageRole.DEVELOPER
    val contents = java.util.List.of(new ConversationMessageContent(m.text))
    new ConversationCapabilityImpl.SimpleMessage(role, m.name.orNull, contents)

  private def toJavaTool(t: ChatTool): ConversationTools =
    val params = mapper
      .readValue(t.parametersJson.value, classOf[java.util.Map[?, ?]])
      .asInstanceOf[java.util.Map[String, Object]]
    val fn = new ConversationToolsFunction(t.name, params)
    t.description.foreach(fn.setDescription)
    new ConversationTools(fn)

private object ConversationCapabilityImpl:
  /** Minimal [[ConversationMessage]] implementation; the SDK ships only the interface. */
  private final class SimpleMessage(
      r: ConversationMessageRole,
      n: String | Null,
      c: java.util.List[ConversationMessageContent],
  ) extends ConversationMessage:
    override def getRole(): ConversationMessageRole = r
    override def getName(): String | Null = n
    override def getContent(): java.util.List[ConversationMessageContent] = c
