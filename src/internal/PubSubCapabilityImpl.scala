package dapr.safe.internal

// NOTE: @assumeSafe would be applied here once Scala 3 stable supports it.
// Currently this annotation is only available in nightly Scala 3 builds.

import dapr.safe.*

import scala.jdk.CollectionConverters.*

private[safe] final class PubSubCapabilityImpl(
    scope: DaprScopeImpl,
    val pubsubName: PubSubName
) extends PubSubCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def publish[T: JsonCodec](topic: Topic, data: T): Unit =
    checkOpen()
    try
      val json = summon[JsonCodec[T]].encode(data)
      scope.daprClient.publishEvent(pubsubName.value, topic.value, json).block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def publishWithMetadata[T: JsonCodec](
      topic: Topic,
      data: T,
      metadata: Map[String, String]
  ): Unit =
    checkOpen()
    try
      val json    = summon[JsonCodec[T]].encode(data)
      val javaMeta: java.util.Map[String, String] = metadata.asJava
      scope.daprClient
        .publishEvent(pubsubName.value, topic.value, json, javaMeta)
        .block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)
