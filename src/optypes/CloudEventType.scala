package dapr.safe

import language.experimental.safe

/** Reverse-DNS event type identifier for a CloudEvent.
  *
  * Must not be empty. Convention dictates a reverse-DNS prefix followed by a dot-separated descriptor, e.g.
  * `"com.example.OrderCreated"`. Consumers typically use this field to dispatch events to the correct handler.
  */
opaque type CloudEventType = String
object CloudEventType:
  def apply(s: String): CloudEventType =
    require(s.nonEmpty, "CloudEventType must not be empty")
    s
  extension (t: CloudEventType) def value: String = t
