package dapr.safe.internal

// NOTE: @assumeSafe would be applied here once Scala 3 stable supports it.
// Currently this annotation is only available in nightly Scala 3 builds.

import dapr.safe.*

import scala.jdk.CollectionConverters.*

private[safe] final class SecretsCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: SecretStoreName
) extends SecretsCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get(key: String): String =
    checkOpen()
    try
      val result: java.util.Map[String, String] =
        scope.daprClient.getSecret(storeName.value, key).block()
      if result == null || result.isEmpty then
        throw DaprException(s"Secret '$key' not found in store '${storeName.value}'")
      val scalaMap = result.asScala
      scalaMap.get(key) match
        case Some(v) => v
        case None =>
          if scalaMap.size == 1 then scalaMap.values.head
          else throw DaprException(s"Secret key '$key' not found in store '${storeName.value}'")
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def getBulk(): Map[String, String] =
    checkOpen()
    try
      val result: java.util.Map[String, java.util.Map[String, String]] =
        scope.daprClient.getBulkSecret(storeName.value).block()
      if result == null then return Map.empty
      result.asScala.flatMap { case (secretKey, subMap) =>
        if subMap == null then Map.empty
        else subMap.asScala.map { case (subKey, v) =>
          s"$secretKey/$subKey" -> v
        }.toMap
      }.toMap
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)
