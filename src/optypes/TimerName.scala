package dapr.safe

import language.experimental.safe

opaque type TimerName = String
object TimerName:
  def apply(s: String): TimerName =
    require(s.nonEmpty, "TimerName must not be empty")
    s
  extension (n: TimerName) def value: String = n
