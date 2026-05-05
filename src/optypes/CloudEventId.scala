package dapr.safe

import language.experimental.safe

/** Unique identifier of a CloudEvent message.
  *
  * Must not be empty. Set by the publisher at event creation time; a UUID is recommended to guarantee global
  * uniqueness. Consumers may use this field for deduplication.
  */
opaque type CloudEventId = String
object CloudEventId:
  def apply(s: String): CloudEventId =
    require(s.nonEmpty, "CloudEventId must not be empty")
    s
  extension (id: CloudEventId) def value: String = id
