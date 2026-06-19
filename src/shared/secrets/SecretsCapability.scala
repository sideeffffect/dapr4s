package dapr4s.secrets

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Accessor (rung 2) for secrets stores: an "any store" handle obtained argument-less via [[DaprCapability.secrets]],
  * whose [[apply]] narrows to a [[SecretsCapability]] bound to one store.
  */
@scala.caps.assumeSafe
trait AccessSecretsCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[SecretsCapability]] for the named secrets store. */
  def apply(storeName: SecretStoreName): SecretsCapability^{this}

/** Capability for reading secrets from a named DAPR secrets store. */
@scala.caps.assumeSafe
trait SecretsCapability extends scala.caps.ExclusiveCapability:
  val storeName: SecretStoreName

  /** Retrieve a single named secret value. Returns `None` if absent.
    *
    * @param metadata
    *   optional metadata passed to the secrets backend
    */
  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty): Option[SecretValue]

  /** Retrieve all secrets in the store as a flat key→value map.
    *
    * @param metadata
    *   optional metadata passed to the secrets backend
    */
  def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[SecretKey, SecretValue]

/** Companion-object API for [[SecretsCapability]].
  *
  * Forwards to the `SecretsCapability` in the enclosing `using` context:
  * {{{
  *   def dbPassword()(using SecretsCapability): String =
  *     SecretsCapability.get(SecretKey("db-password")).getOrElse("default")
  * }}}
  */
@scala.caps.assumeSafe
object SecretsCapability:
  def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
      cap: SecretsCapability,
  ): Option[SecretValue] =
    cap.get(key, metadata)
  def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
      cap: SecretsCapability,
  ): Map[SecretKey, SecretValue] =
    cap.getBulk(metadata)

