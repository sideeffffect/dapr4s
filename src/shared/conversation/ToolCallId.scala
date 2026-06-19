package dapr4s.conversation

import dapr4s.*

/** The provider-assigned id of a tool call, used to correlate a [[ConversationToolCall]] with the tool result message
  * sent back in a follow-up turn.
  */
opaque type ToolCallId = String

object ToolCallId:
  def apply(value: String): ToolCallId = value
  extension (id: ToolCallId) def value: String = id
