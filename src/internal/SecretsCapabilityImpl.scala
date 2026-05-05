package dapr.safe.internal

import dapr.safe.*

import scala.jdk.CollectionConverters.*
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[safe] final class SecretsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: SecretStoreName,
) extends SecretsCapability:

  def get(key: SecretKey): Option[String] =
    scope.client
      .getSecret(storeName.value, key.value)
      .awaitResult()
      .toOption
      .filterNot(_.isEmpty)
      .flatMap { m =>
        val sm = m.asScala
        sm.get(key.value).orElse(if sm.sizeIs == 1 then sm.valuesIterator.nextOption() else None)
      }

  def getBulk(): Map[SecretKey, String] =
    scope.client
      .getBulkSecret(storeName.value)
      .awaitResult()
      .toOption
      .fold(Map.empty) { result =>
        result.asScala.flatMap { case (secretKey, subMap) =>
          subMap.toOption.fold(Map.empty[SecretKey, String]) { sm =>
            sm.asScala.map { case (subKey, v) => SecretKey(s"$secretKey/$subKey") -> v }.toMap
          }
        }.toMap
      }
