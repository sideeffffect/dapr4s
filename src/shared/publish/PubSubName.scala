package dapr4s.publish

import dapr4s.*

import language.experimental.safe

/** Name of a Dapr pub/sub component.
  *
  * Must not be empty. Must match the `name` field in the pub/sub component's metadata YAML. Used when constructing a
  * [[PublishCapability]] via [[DaprCapability.publish]] and appears in incoming [[CloudEvent]] messages as the
  * `pubSubName` field.
  */
opaque type PubSubName = String
object PubSubName:
  def apply(s: String): PubSubName =
    require(s.nonEmpty, "PubSubName must not be empty")
    s
  extension (n: PubSubName) def value: String = n
