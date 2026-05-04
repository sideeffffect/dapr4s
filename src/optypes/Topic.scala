package dapr.safe

import language.experimental.safe

opaque type Topic = String
object Topic:
  def apply(s: String): Topic =
    require(s.nonEmpty, "Topic must not be empty")
    s
  extension (n: Topic) def value: String = n
