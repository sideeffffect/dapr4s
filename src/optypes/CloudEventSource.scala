package dapr.safe

import language.experimental.safe

opaque type CloudEventSource = String
object CloudEventSource:
  def apply(s: String): CloudEventSource =
    require(s.nonEmpty, "CloudEventSource must not be empty")
    s
  extension (src: CloudEventSource) def value: String = src
