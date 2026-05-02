package dapr.safe.internal

import dapr.safe.*
import language.experimental.saferExceptions

import scala.jdk.CollectionConverters.*

@scala.caps.assumeSafe
private[safe] final class PubSubCapabilityImpl(
    scope: DaprScopeImpl,
    val pubsubName: PubSubName
) extends PubSubCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def publish[T: JsonCodec](topic: Topic, data: T): Unit throws DaprPubSubException =
    checkOpen()
    try
      val json = summon[JsonCodec[T]].encode(data)
      scope.daprClient.publishEvent(pubsubName.value, topic.value, json).block(): Unit
    catch
      case e: DaprPubSubException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprPubSubException(e.getMessage.nn, e)

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  ): Unit throws DaprPubSubException =
    checkOpen()
    try
      val json    = summon[JsonCodec[T]].encode(data)
      val javaMeta: java.util.Map[String, String] = metadata.asJava
      scope.daprClient
        .publishEvent(pubsubName.value, topic.value, json, javaMeta)
        .block(): Unit
    catch
      case e: DaprPubSubException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprPubSubException(e.getMessage.nn, e)

  def bulkPublish[T: JsonCodec](topic: Topic, entries: Seq[BulkPublishEntry[T]]): BulkPublishResult throws DaprPubSubException =
    checkOpen()
    try
      val previewClient = scope.daprClient.asInstanceOf[io.dapr.client.DaprPreviewClient]
      val javaEntries: java.util.List[io.dapr.client.domain.BulkPublishEntry[String]] =
        entries.map { entry =>
          val json = summon[JsonCodec[T]].encode(entry.event)
          new io.dapr.client.domain.BulkPublishEntry[String](
            entry.entryId,
            json,
            "application/json"
          )
        }.asJava
      // publishEvents with a List of BulkPublishEntry[String] returns BulkPublishResponse[BulkPublishEntry[String]]
      val response =
        previewClient.publishEvents(pubsubName.value, topic.value, "application/json", javaEntries).block()
      if response == null then return BulkPublishResult(List.empty)
      val failedItems = response.getFailedEntries
      if failedItems == null then BulkPublishResult(List.empty)
      else
        val failedIds = failedItems.asScala.map { item =>
          val e = item.getEntry
          if e == null then "" else e.getEntryId.nn
        }.filter(_.nonEmpty).toList
        BulkPublishResult(failedIds)
    catch
      case e: DaprPubSubException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprPubSubException(e.getMessage.nn, e)
      case e: ClassCastException =>
        throw DaprPubSubException("bulkPublish requires DaprPreviewClient (not available)", e)
