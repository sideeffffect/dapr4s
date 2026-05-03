package dapr.safe.internal

import dapr.safe.*

import scala.jdk.CollectionConverters.*
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class SecretsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: SecretStoreName,
) extends SecretsCapability:

  def get(key: SecretKey): Option[String] =
    val result: java.util.Map[String, String] | Null =
      scope.client.getSecret(storeName.value, key.value).awaitResult()
    if result == null || result.isEmpty then return None
    val scalaMap = result.asScala
    scalaMap.get(key.value) match
      case Some(v) => Some(v)
      case None    =>
        scalaMap.valuesIterator.nextOption() match
          case Some(v) if scalaMap.sizeIs == 1 => Some(v)
          case _                               => None

  def getBulk(): Map[SecretKey, String] =
    val result: java.util.Map[String, java.util.Map[String, String]] | Null =
      scope.client.getBulkSecret(storeName.value).awaitResult()
    if result == null then return Map.empty
    result.asScala.flatMap { case (secretKey, subMap) =>
      if subMap == null then Map.empty
      else
        subMap.asScala.map { case (subKey, v) =>
          SecretKey(s"$secretKey/$subKey") -> v
        }.toMap
    }.toMap
