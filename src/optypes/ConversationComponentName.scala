package dapr4s

import language.experimental.safe

/** Name of a Dapr conversation (LLM) component.
  *
  * Must not be empty. Must match the `name` field in the conversation component's metadata YAML. Used when constructing
  * a [[ConversationCapability]] via [[DaprCapability.conversation]].
  */
opaque type ConversationComponentName = String
object ConversationComponentName:
  def apply(s: String): ConversationComponentName =
    require(s.nonEmpty, "ConversationComponentName must not be empty")
    s
  extension (n: ConversationComponentName) def value: String = n
