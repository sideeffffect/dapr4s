package dapr.safe

import language.experimental.safe

opaque type EventName = String
object EventName:
  def apply(s: String): EventName =
    require(s.nonEmpty, "EventName must not be empty")
    s
  extension (n: EventName) def value: String = n
