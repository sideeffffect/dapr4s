package dapr4s

/** The name of a function/tool the model may call.
  *
  * Used both when declaring a tool ([[ConversationTools.name]]) and when the model emits a call
  * ([[ConversationToolCalls.functionName]]).
  */
opaque type ToolName = String

@scala.caps.assumeSafe
object ToolName:
  def apply(value: String): ToolName = value
  extension (n: ToolName) def value: String = n
