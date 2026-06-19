package dapr4s.conversation

import dapr4s.*

/** The name of a function/tool the model may call.
  *
  * Used both when declaring a tool ([[ConversationTool.name]]) and when the model emits a call
  * ([[ConversationToolCall.functionName]]).
  */
opaque type ToolName = String

@scala.caps.assumeSafe
object ToolName:
  def apply(value: String): ToolName = value
  extension (n: ToolName) def value: String = n
