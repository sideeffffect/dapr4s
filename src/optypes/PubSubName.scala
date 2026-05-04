package dapr.safe

import language.experimental.safe

opaque type PubSubName = String
object PubSubName:
  def apply(s: String): PubSubName =
    require(s.nonEmpty, "PubSubName must not be empty")
    s
  extension (n: PubSubName) def value: String = n
