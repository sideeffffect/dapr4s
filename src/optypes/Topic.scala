package dapr4s

import language.experimental.safe

/** Pub/sub topic name.
  *
  * Must not be empty. Must match the topic name registered in both the publisher ([[PubSubCapability.publish]]) and the
  * subscriber configuration so that messages are routed correctly through the Dapr pub/sub component.
  */
opaque type Topic = String
object Topic:
  def apply(s: String): Topic =
    require(s.nonEmpty, "Topic must not be empty")
    s
  extension (n: Topic) def value: String = n
