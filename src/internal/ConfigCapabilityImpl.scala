package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{ConfigurationItem as JConfigItem}
import language.experimental.saferExceptions

import scala.jdk.CollectionConverters.*

@scala.caps.assumeSafe
private[safe] final class ConfigCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: ConfigStoreName
) extends ConfigurationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(keys: Seq[String]): Map[String, ConfigItem] throws DaprConfigurationException =
    checkOpen()
    try
      val javaKeys: java.util.List[String] = keys.asJava
      val emptyMeta: java.util.Map[String, String] = java.util.Collections.emptyMap()
      val result: java.util.Map[String, JConfigItem] | Null =
        scope.daprClient.getConfiguration(storeName.value, javaKeys, emptyMeta).block()
      if result == null then return Map.empty
      result.asScala.map { case (k, item) =>
        val v: String | Null       = item.getValue
        val ver: String | Null     = item.getVersion
        val meta: java.util.Map[String, String] | Null = item.getMetadata
        k -> ConfigItem(
          key      = k,
          value    = if v == null then "" else v,
          version  = if ver == null then "" else ver,
          metadata = if meta == null then Map.empty else meta.asScala.toMap
        )
      }.toMap
    catch
      case e: DaprConfigurationException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprConfigurationException(e.getMessage.nn, e)
