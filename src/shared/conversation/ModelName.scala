package dapr4s.conversation

import dapr4s.*

/** The model identifier a provider reports for a completion (e.g. "gpt-4o").
  *
  * Read from [[ConversationResult.model]].
  */
opaque type ModelName = String

object ModelName:
  def apply(value: String): ModelName = value
  extension (n: ModelName) def value: String = n
