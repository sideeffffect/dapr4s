package dapr.safe

import language.experimental.safe

opaque type CloudEventSpecVersion = String
object CloudEventSpecVersion:
  def apply(s: String): CloudEventSpecVersion =
    require(s.nonEmpty, "CloudEventSpecVersion must not be empty")
    s
  extension (v: CloudEventSpecVersion) def value: String = v
