//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import io.dapr.client.domain.{ConfigurationItem as JConfigItem, SubscribeConfigurationResponse}
import java.util.logging.{Level, Logger}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[internal] final class ConfigurationCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: ConfigurationStoreName,
) extends ConfigurationCapability:

  import ConfigurationCapabilityImpl.*

  def get(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[ConfigurationKey, ConfigurationItem] =
    val javaKeys: java.util.List[String] = keys.map(_.value).asJava
    val javaMeta = toJavaMeta(metadata)
    scope.client
      .getConfiguration(storeName.value, javaKeys, javaMeta)
      .awaitResult()
      .toOption
      .fold(Map.empty)(_.asScala.map { case (k, item) => ConfigurationKey(k) -> toConfigItem(k, item) }.toMap)

  def subscribe(keys: Seq[ConfigurationKey], metadata: Map[MetadataKey, MetadataValue] = Map.empty)(
      onChange: ConfigurationUpdate => Unit,
  ): AutoCloseable^{this} =
    val javaKeys: java.util.List[String] = keys.map(_.value).asJava
    val javaMeta = toJavaMeta(metadata)
    val storeNameStr = storeName.value
    val flux = scope.client.subscribeConfiguration(storeNameStr, javaKeys, javaMeta)
    val sub = flux.subscribe { (response: SubscribeConfigurationResponse | Null) =>
      response.toOption.foreach { r =>
        r.getItems.toOption.foreach { jItems =>
          val items = jItems.asScala.map { case (k, item) => ConfigurationKey(k) -> toConfigItem(k, item) }.toMap
          try onChange(ConfigurationUpdate(ConfigurationStoreName(storeNameStr), items))
          catch case NonFatal(e) => log.log(Level.WARNING, "Config subscription onChange callback threw", e)
        }
      }
    }
    () => sub.dispose()

@scala.caps.assumeSafe
private object ConfigurationCapabilityImpl:
  private val log = Logger.getLogger("dapr4s.internal.ConfigurationCapabilityImpl")

  private def toConfigItem(k: String, item: JConfigItem): ConfigurationItem =
    ConfigurationItem(
      key = ConfigurationKey(k),
      value = ConfigurationValue(item.getValue.toOption.getOrElse("")),
      version = ConfigurationVersion(item.getVersion.toOption.getOrElse("")),
      metadata = item.getMetadata.toOption.fold(Map.empty[MetadataKey, MetadataValue]) { jm =>
        jm.asScala.map { case (mk, mv) => MetadataKey(mk) -> MetadataValue(mv) }.toMap
      },
    )

  private def toJavaMeta(m: Map[MetadataKey, MetadataValue]): java.util.Map[String, String] =
    m.map { case (k, v) => k.value -> v.value }.asJava
