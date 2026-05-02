package dapr.safe.internal

// NOTE: @assumeSafe would be applied here once Scala 3 stable supports it.
// Currently this annotation is only available in nightly Scala 3 builds.

import dapr.safe.*
import io.dapr.client.domain.{ConfigurationItem as JConfigItem}

import scala.jdk.CollectionConverters.*

private[safe] final class ConfigCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: ConfigStoreName
) extends ConfigurationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(keys: String*): Map[String, ConfigItem] =
    checkOpen()
    try
      val javaKeys: java.util.List[String] = keys.toList.asJava
      val emptyMeta: java.util.Map[String, String] = java.util.Collections.emptyMap()
      val result: java.util.Map[String, JConfigItem] =
        scope.daprClient.getConfiguration(storeName.value, javaKeys, emptyMeta).block()
      if result == null then return Map.empty
      result.asScala.map { case (k, item) =>
        k -> ConfigItem(
          key      = k,
          value    = if item.getValue == null then "" else item.getValue,
          version  = if item.getVersion == null then "" else item.getVersion,
          metadata = if item.getMetadata == null then Map.empty
                     else item.getMetadata.asScala.toMap
        )
      }.toMap
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)
