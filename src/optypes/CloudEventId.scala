package dapr.safe

import language.experimental.safe

opaque type CloudEventId = String
object CloudEventId:
  def apply(s: String): CloudEventId =
    require(s.nonEmpty, "CloudEventId must not be empty")
    s
  extension (id: CloudEventId) def value: String = id
