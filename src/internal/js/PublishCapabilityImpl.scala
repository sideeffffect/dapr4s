//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import JsInterop.*

@scala.caps.assumeSafe
private[internal] final class PublishCapabilityImpl(
    scope: DaprCapabilityImpl,
    val pubsubName: PubSubName,
) extends PublishCapability:

  import PublishCapabilityImpl.*

  // The pre-encoded JSON is parsed into a JS value and published with an explicit
  // contentType = "application/json": with the header set, the SDK's serializeHttp
  // (utils/Serializer.util.js) takes the JSON branch and JSON.stringify-s the value — yielding
  // exactly our original document on the wire with the application/json content type, identical
  // to the JVM impl (raw JSON bytes). WITHOUT the override the SDK would INFER the content type
  // from the JS value (utils/Client.util.js getContentType), and a JSON scalar payload (string/
  // number/boolean) would be inferred as text/plain and sent via toString() — e.g. the JSON
  // string "hi" would arrive as the 2 bytes `hi` instead of the 4 bytes `"hi"`.
  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    val json = summon[JsonCodec[T]].encode(data)
    val response = JsAwait.await(
      scope.client.pubsub.publish(pubsubName.value, topic.value, parseJson(json), jsonContentTypeOptions),
    )
    throwIfFailed(response)

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    val json = summon[JsonCodec[T]].encode(data)
    val options = new facade.PubSubPublishOptions(contentType = "application/json", metadata = toDict(metadata))
    val response = JsAwait.await(scope.client.pubsub.publish(pubsubName.value, topic.value, parseJson(json), options))
    throwIfFailed(response)

  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult =
    // The explicit {entryID, event, contentType} message shape keeps our entry IDs authoritative
    // (the SDK generates random UUIDs otherwise — utils/Client.util.js getBulkPublishEntries) and
    // pins application/json per entry for the same scalar-payload reason as publish above.
    val messages = entries.map { entry =>
      new facade.PubSubBulkPublishMessage(
        entryID = entry.entryId.value,
        event = parseJson(summon[JsonCodec[T]].encode(entry.event)),
        contentType = "application/json",
      )
    }.toJSArray
    val response = JsAwait.await(scope.client.pubsub.publishBulk(pubsubName.value, topic.value, messages))
    val failedIds = response.failedMessages.toList.map(fm => BulkEntryId(fm.message.entryID))
    BulkPublishResult(failedIds)

@scala.caps.assumeSafe
private object PublishCapabilityImpl:
  private val jsonContentTypeOptions = new facade.PubSubPublishOptions(contentType = "application/json")

  /** `pubsub.publish` soft-fails (`{error}` instead of rejecting — `implementation/Client/HTTPClient/pubsub.js`);
    * rethrow to mirror the JVM impl, where a failed `publishEvent` throws `DaprException`.
    */
  private def throwIfFailed(response: facade.SoftFailureResponse): Unit =
    response.error.toOption.foreach(e => throw js.JavaScriptException(e))
