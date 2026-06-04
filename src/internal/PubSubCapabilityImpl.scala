package dapr4s.internal

import dapr4s.*

import scala.jdk.CollectionConverters.*
import MonoOps.*
import java.nio.charset.StandardCharsets.UTF_8

@scala.caps.assumeSafe
private[internal] final class PubSubCapabilityImpl(
    scope: DaprCapabilityImpl,
    val pubsubName: PubSubName,
) extends PubSubCapability:

  // Publish the already-encoded JSON as raw bytes: the Dapr SDK's serializer passes byte[] through
  // untouched but would re-serialize a String, double-encoding the event data into a JSON string
  // (which subscribers then fail to decode as an object).
  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    val json = summon[JsonCodec[T]].encode(data).getBytes(UTF_8)
    scope.client.publishEvent(pubsubName.value, topic.value, json).awaitResult(): Unit

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    val json = summon[JsonCodec[T]].encode(data).getBytes(UTF_8)
    val javaMeta: java.util.Map[String, String] = metadata.map { case (k, v) => k.value -> v.value }.asJava
    scope.client
      .publishEvent(pubsubName.value, topic.value, json, javaMeta)
      .awaitResult(): Unit

  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult =
    val javaEntries: java.util.List[io.dapr.client.domain.BulkPublishEntry[String]] =
      entries.map { entry =>
        val json = summon[JsonCodec[T]].encode(entry.event)
        new io.dapr.client.domain.BulkPublishEntry[String](
          entry.entryId.value,
          json,
          "application/json",
        )
      }.asJava
    val response =
      scope.client.publishEvents(pubsubName.value, topic.value, "application/json", javaEntries).awaitResult()
    if response == null then return BulkPublishResult(List.empty)
    val failedItems = response.getFailedEntries
    if failedItems == null then BulkPublishResult(List.empty)
    else
      val failedIds = failedItems.asScala
        .map { item =>
          val e = item.getEntry
          if e == null then "" else e.getEntryId.nn
        }
        .filter(_.nonEmpty)
        .map(BulkEntryId(_))
        .toList
      BulkPublishResult(failedIds)
