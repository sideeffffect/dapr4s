package dapr4s.publish

import dapr4s.*

import language.experimental.safe

/** An entry in a bulk publish request. */
final case class BulkPublishEntry[T](entryId: BulkEntryId, event: T)

/** Result of a bulk publish — contains IDs of any failed entries. */
final case class BulkPublishResult(failedEntries: List[BulkEntryId])

/** What a subscription handler should do with the received message. */
enum SubscriptionResult:
  /** ACK — do not redeliver. */
  case Success

  /** NAK — redeliver after the configured retry interval. */
  case Retry

  /** Silently discard — do not redeliver, do not report an error. */
  case Drop

/** A CloudEvent envelope wrapping an inbound pub/sub message delivered by the Dapr sidecar.
  *
  * The sidecar deserialises the raw message from the broker into this structure before calling the subscription
  * handler. `data` is the typed payload; all other fields come from the CloudEvents envelope.
  *
  * @param id
  *   Unique event identifier (UUID).
  * @param source
  *   URI-reference identifying the event producer (e.g. `"/orders/service"`).
  * @param specVersion
  *   CloudEvents specification version (e.g. `"1.0"`).
  * @param eventType
  *   Reverse-DNS event type (e.g. `"com.example.OrderCreated"`).
  * @param topic
  *   The pub/sub topic on which the event arrived.
  * @param pubSubName
  *   The Dapr pub/sub component that delivered the event.
  * @param dataContentType
  *   MIME type of the raw payload (e.g. `"application/json"`).
  * @param data
  *   The deserialised event payload.
  */
final case class CloudEvent[T](
    id: CloudEventId,
    source: CloudEventSource,
    specVersion: CloudEventSpecVersion,
    eventType: CloudEventType,
    topic: Topic,
    pubSubName: PubSubName,
    dataContentType: ContentType,
    data: T,
)
