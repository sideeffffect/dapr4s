package dapr4s

import language.experimental.safe

/** Origin of a CloudEvent, expressed as a URI-reference identifying the event source.
  *
  * Must not be empty. Typical values are path-like strings such as `"/orders/service"` or a full URI. Per the
  * CloudEvents specification this field provides context about the event producer and may be used by consumers for
  * routing or filtering.
  */
opaque type CloudEventSource = String
object CloudEventSource:
  def apply(s: String): CloudEventSource =
    require(s.nonEmpty, "CloudEventSource must not be empty")
    s
  extension (src: CloudEventSource) def value: String = src
