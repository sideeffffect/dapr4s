package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{ConfigurationItem as JConfigItem, SubscribeConfigurationResponse}
import language.experimental.saferExceptions

import scala.jdk.CollectionConverters.*
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class ConfigCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: ConfigStoreName
) extends ConfigurationCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(keys: Seq[ConfigKey]): Map[ConfigKey, ConfigItem] throws DaprConfigurationException =
    checkOpen()
    try
      val javaKeys: java.util.List[String] = keys.map(_.value).asJava
      val emptyMeta: java.util.Map[String, String] = java.util.Collections.emptyMap()
      val result: java.util.Map[String, JConfigItem] | Null =
        scope.client.getConfiguration(storeName.value, javaKeys, emptyMeta).awaitResult()
      if result == null then return Map.empty
      result.asScala.map { case (k, item) =>
        val v: String | Null       = item.getValue
        val ver: String | Null     = item.getVersion
        val meta: java.util.Map[String, String] | Null = item.getMetadata
        ConfigKey(k) -> ConfigItem(
          key      = ConfigKey(k),
          value    = if v == null then "" else v,
          version  = if ver == null then "" else ver,
          metadata = if meta == null then Map.empty else meta.asScala.toMap
        )
      }.toMap
    catch
      case e: DaprConfigurationException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprConfigurationException(e.getMessage.nn, e)

  def subscribe(keys: Seq[ConfigKey])(onChange: ConfigUpdate => Unit): AutoCloseable throws DaprConfigurationException =
    checkOpen()
    try
      val javaKeys: java.util.List[String] = keys.map(_.value).asJava
      val storeNameStr = storeName.value
      val flux = scope.client.subscribeConfiguration(storeNameStr, javaKeys, java.util.Collections.emptyMap())
      val sub = flux.subscribe { (response: SubscribeConfigurationResponse | Null) =>
        if response != null then
          val jItems: java.util.Map[String, JConfigItem] | Null = response.getItems
          if jItems != null then
            val items = jItems.asScala.map { case (k, item) =>
              val v: String | Null   = item.getValue
              val ver: String | Null = item.getVersion
              val meta: java.util.Map[String, String] | Null = item.getMetadata
              ConfigKey(k) -> ConfigItem(
                key      = ConfigKey(k),
                value    = if v == null then "" else v,
                version  = if ver == null then "" else ver,
                metadata = if meta == null then Map.empty else meta.asScala.toMap
              )
            }.toMap
            try onChange(ConfigUpdate(ConfigStoreName(storeNameStr), items))
            catch case _: Exception => ()
      }
      () => sub.dispose()
    catch
      case e: DaprConfigurationException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprConfigurationException(e.getMessage.nn, e)
