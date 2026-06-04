package dapr4s.internal

import dapr4s.*

import scala.jdk.CollectionConverters.*
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[dapr4s] final class SecretsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: SecretStoreName,
) extends SecretsCapability:

  import SecretsCapabilityImpl.toJavaMeta

  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty): Option[SecretValue] =
    val javaMeta = toJavaMeta(metadata)
    scope.client
      .getSecret(storeName.value, key.value, javaMeta)
      .awaitResult()
      .toOption
      .filterNot(_.isEmpty)
      .flatMap { m =>
        val sm = m.asScala
        sm.get(key.value)
          .orElse(if sm.sizeIs == 1 then sm.valuesIterator.nextOption() else None)
          .map(SecretValue(_))
      }

  def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[SecretKey, SecretValue] =
    val javaMeta = toJavaMeta(metadata)
    scope.client
      .getBulkSecret(storeName.value, javaMeta)
      .awaitResult()
      .toOption
      .fold(Map.empty) { result =>
        result.asScala.flatMap { case (secretKey, subMap) =>
          subMap.toOption.fold(Map.empty[SecretKey, SecretValue]) { sm =>
            sm.asScala.map { case (subKey, v) => SecretKey(s"$secretKey/$subKey") -> SecretValue(v) }.toMap
          }
        }.toMap
      }

@scala.caps.assumeSafe
private object SecretsCapabilityImpl:
  def toJavaMeta(m: Map[MetadataKey, MetadataValue]): java.util.Map[String, String] =
    m.map { case (k, v) => k.value -> v.value }.asJava
