package dapr.safe

import language.experimental.safe

// CloudEvent field opaque types — kept in a separate file from OpaqueTypes.scala
// because the CC compiler exhibits exponential compile-time growth once a
// safe-mode file exceeds ~25 opaque String types with `.value` extensions.
// OpaqueTypes.scala holds the other 25; adding more to either file risks
// crossing that threshold.

opaque type CloudEventId = String
object CloudEventId:
  def apply(s: String): CloudEventId =
    require(s.nonEmpty, "CloudEventId must not be empty")
    s
  extension (id: CloudEventId) def value: String = id

opaque type CloudEventSource = String
object CloudEventSource:
  def apply(s: String): CloudEventSource =
    require(s.nonEmpty, "CloudEventSource must not be empty")
    s
  extension (src: CloudEventSource) def value: String = src

opaque type CloudEventType = String
object CloudEventType:
  def apply(s: String): CloudEventType =
    require(s.nonEmpty, "CloudEventType must not be empty")
    s
  extension (t: CloudEventType) def value: String = t

opaque type ContentType = String
object ContentType:
  def apply(s: String): ContentType =
    require(s.nonEmpty, "ContentType must not be empty")
    s
  extension (ct: ContentType) def value: String = ct

opaque type CloudEventSpecVersion = String
object CloudEventSpecVersion:
  def apply(s: String): CloudEventSpecVersion =
    require(s.nonEmpty, "CloudEventSpecVersion must not be empty")
    s
  extension (v: CloudEventSpecVersion) def value: String = v
