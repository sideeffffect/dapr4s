package dapr.safe.internal

import dapr.safe.*
import language.experimental.saferExceptions

import scala.jdk.CollectionConverters.*
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class SecretsCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: SecretStoreName
) extends SecretsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(key: String): String throws DaprSecretsException =
    checkOpen()
    try
      val result: java.util.Map[String, String] | Null =
        scope.client.getSecret(storeName.value, key).awaitResult()
      if result == null || result.isEmpty then
        throw DaprSecretsException(s"Secret '$key' not found in store '${storeName.value}'")
      val scalaMap = result.asScala
      scalaMap.get(key) match
        case Some(v) => v
        case None =>
          if scalaMap.size == 1 then scalaMap.values.head
          else throw DaprSecretsException(s"Secret key '$key' not found in store '${storeName.value}'")
    catch
      case e: DaprSecretsException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprSecretsException(e.getMessage.nn, e)

  def getBulk(): Map[String, String] throws DaprSecretsException =
    checkOpen()
    try
      val result: java.util.Map[String, java.util.Map[String, String]] | Null =
        scope.client.getBulkSecret(storeName.value).awaitResult()
      if result == null then return Map.empty
      result.asScala.flatMap { case (secretKey, subMap) =>
        if subMap == null then Map.empty
        else subMap.asScala.map { case (subKey, v) =>
          s"$secretKey/$subKey" -> v
        }.toMap
      }.toMap
    catch
      case e: DaprSecretsException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprSecretsException(e.getMessage.nn, e)
