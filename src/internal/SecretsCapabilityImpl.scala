package dapr.safe.internal

import dapr.safe.*
import language.experimental.saferExceptions

import scala.jdk.CollectionConverters.*
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class SecretsCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: SecretStoreName
) extends SecretsCapability:

  def get(key: SecretKey): String throws DaprSecretsException =
    try
      val result: java.util.Map[String, String] | Null =
        scope.client.getSecret(storeName.value, key.value).awaitResult()
      if result == null || result.isEmpty then
        throw DaprSecretsException(s"Secret '${key.value}' not found in store '${storeName.value}'")
      val scalaMap = result.asScala
      scalaMap.get(key.value) match
        case Some(v) => v
        case None =>
          scalaMap.valuesIterator.nextOption() match
            case Some(v) if scalaMap.sizeIs == 1 => v
            case _ => throw DaprSecretsException(s"Secret key '${key.value}' not found in store '${storeName.value}'")
    catch
      case e: DaprSecretsException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprSecretsException(e.getMessage.nn, e)

  def getBulk(): Map[SecretKey, String] throws DaprSecretsException =
    try
      val result: java.util.Map[String, java.util.Map[String, String]] | Null =
        scope.client.getBulkSecret(storeName.value).awaitResult()
      if result == null then return Map.empty
      result.asScala.flatMap { case (secretKey, subMap) =>
        if subMap == null then Map.empty
        else subMap.asScala.map { case (subKey, v) =>
          SecretKey(s"$secretKey/$subKey") -> v
        }.toMap
      }.toMap
    catch
      case e: DaprSecretsException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprSecretsException(e.getMessage.nn, e)
