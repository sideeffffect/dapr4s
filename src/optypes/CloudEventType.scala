package dapr.safe

import language.experimental.safe

opaque type CloudEventType = String
object CloudEventType:
  def apply(s: String): CloudEventType =
    require(s.nonEmpty, "CloudEventType must not be empty")
    s
  extension (t: CloudEventType) def value: String = t
