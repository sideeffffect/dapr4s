package dapr4s.publish

import dapr4s.*

import language.experimental.safe

/** CloudEvents specification version string.
  *
  * Must not be empty. The current widely-deployed version is `"1.0"`. Dapr sets this automatically when publishing
  * events; it appears in the [[CloudEvent]] envelope when receiving messages.
  */
opaque type CloudEventSpecVersion = String
object CloudEventSpecVersion:
  def apply(s: String): CloudEventSpecVersion =
    require(s.nonEmpty, "CloudEventSpecVersion must not be empty")
    s
  extension (v: CloudEventSpecVersion) def value: String = v
