package dapr4s

/** A conversation continuation token returned by the model and replayed to continue a multi-turn exchange.
  *
  * Pass into [[ConversationCapability.converse]] / [[ConversationCapability.chat]] to continue a prior conversation;
  * read back from [[ChatResponse.contextId]].
  */
opaque type ConversationContextId = String

object ConversationContextId:
  def apply(value: String): ConversationContextId = value
  extension (id: ConversationContextId) def value: String = id
