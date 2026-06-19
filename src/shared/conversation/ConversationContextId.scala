package dapr4s.conversation

import dapr4s.*

/** A conversation continuation token returned by the model and replayed to continue a multi-turn exchange.
  *
  * Pass into [[ConversationCapability.converse]] to continue a prior conversation; read back from
  * [[ConversationResponse.contextId]].
  */
opaque type ConversationContextId = String

object ConversationContextId:
  def apply(value: String): ConversationContextId = value
  extension (id: ConversationContextId) def value: String = id
