package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{ConfigurationItem as JConfigItem, SubscribeConfigurationResponse}
import java.util.logging.{Level, Logger}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[safe] final class ConfigCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: ConfigStoreName,
) extends ConfigurationCapability:

  private val log = Logger.getLogger("dapr.safe.internal.ConfigCapabilityImpl")

  def get(keys: Seq[ConfigKey]): Map[ConfigKey, ConfigItem] =
    val javaKeys: java.util.List[String] = keys.map(_.value).asJava
    val emptyMeta: java.util.Map[String, String] = java.util.Collections.emptyMap()
    scope.client
      .getConfiguration(storeName.value, javaKeys, emptyMeta)
      .awaitResult()
      .toOption
      .fold(Map.empty)(_.asScala.map { case (k, item) => ConfigKey(k) -> toConfigItem(k, item) }.toMap)

  def subscribe(keys: Seq[ConfigKey])(onChange: ConfigUpdate => Unit): AutoCloseable =
    val javaKeys: java.util.List[String] = keys.map(_.value).asJava
    val storeNameStr = storeName.value
    val flux = scope.client.subscribeConfiguration(storeNameStr, javaKeys, java.util.Collections.emptyMap())
    val sub = flux.subscribe { (response: SubscribeConfigurationResponse | Null) =>
      response.toOption.foreach { r =>
        r.getItems.toOption.foreach { jItems =>
          val items = jItems.asScala.map { case (k, item) => ConfigKey(k) -> toConfigItem(k, item) }.toMap
          try onChange(ConfigUpdate(ConfigStoreName(storeNameStr), items))
          catch case NonFatal(e) => log.log(Level.WARNING, "Config subscription onChange callback threw", e)
        }
      }
    }
    () => sub.dispose()

  private def toConfigItem(k: String, item: JConfigItem): ConfigItem =
    ConfigItem(
      key = ConfigKey(k),
      value = item.getValue.toOption.getOrElse(""),
      version = item.getVersion.toOption.getOrElse(""),
      metadata = item.getMetadata.toOption.fold(Map.empty)(_.asScala.toMap),
    )
