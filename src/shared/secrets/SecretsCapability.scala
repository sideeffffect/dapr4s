package dapr4s.secrets

import dapr4s.*

/** Universal capability for DAPR secrets: reaches **any** secrets store, so every operation names its store.
  *
  * Obtained via [[DaprCapability.secrets]]. The capability family has four interfaces, all in this companion:
  *
  *   - [[SecretsCapability]] — any store, full authority (this trait; adds `getBulk`)
  *   - [[SecretsCapability.ReadOnly]] — any store, single-secret reads only (`get`)
  *   - [[SecretsCapability.Instance]] — one store, full authority
  *   - [[SecretsCapability.Instance.ReadOnly]] — one store, single-secret reads only
  *
  * Narrow by store with [[apply]] (`secrets(storeName)`), and by authority by upcasting to the `ReadOnly` super-trait.
  * The two narrowings commute: `apply` on a [[SecretsCapability.ReadOnly]] yields a
  * [[SecretsCapability.Instance.ReadOnly]]. `getBulk` is withheld from the read-only tier because it dumps the whole
  * store — a key-scoped reader should not be able to enumerate every secret.
  */
@scala.caps.assumeSafe
trait SecretsCapability extends SecretsCapability.ReadOnly:
  /** Narrow to the named secrets store, keeping full authority. */
  override def instance(storeName: SecretStoreName): SecretsCapability.Instance^{this}

  /** Retrieve all secrets in the store; see [[SecretsCapability.Instance.getBulk]]. */
  def getBulk(
      storeName: SecretStoreName,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  ): Map[SecretKey, SecretValue]

/** The [[SecretsCapability]] family: the read-only universal view, the store-bound instance views, and the
  * companion-object forwarders for each.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def dbPassword()(using SecretsCapability.Instance.ReadOnly): Option[SecretValue] =
  *     SecretsCapability.Instance.ReadOnly.get(SecretKey("db-password"))
  * }}}
  */
@scala.caps.assumeSafe
object SecretsCapability:

  /** Read-only view of [[SecretsCapability]]: reaches any store, but only reads single secrets. */
  @scala.caps.assumeSafe
  trait ReadOnly extends scala.caps.ExclusiveCapability:
    /** Narrow to the named secrets store, keeping read-only authority. */
    def instance(storeName: SecretStoreName): Instance.ReadOnly^{this}

    /** Retrieve a single named secret value; see [[SecretsCapability.Instance.ReadOnly.get]]. */
    def get(
        storeName: SecretStoreName,
        key: SecretKey,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    ): Option[SecretValue]

  /** Forwarders for [[SecretsCapability.ReadOnly]]. */
  @scala.caps.assumeSafe
  object ReadOnly:
    /** Narrow the read-only capability in scope to one store and return it. */
    def instance(storeName: SecretStoreName)(using cap: ReadOnly): Instance.ReadOnly^{cap} =
      cap.instance(storeName)
    def get(
        storeName: SecretStoreName,
        key: SecretKey,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
    )(using cap: ReadOnly): Option[SecretValue] =
      cap.get(storeName, key, metadata)

  /** Capability bound to one named secrets store, full authority. Obtained via `secrets(storeName)`. */
  @scala.caps.assumeSafe
  trait Instance extends Instance.ReadOnly:
    /** Retrieve all secrets in the store as a flat key→value map.
      *
      * @param metadata
      *   optional metadata passed to the secrets backend
      */
    def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty): Map[SecretKey, SecretValue]

  /** [[SecretsCapability.Instance]]'s read-only view, and the forwarders for [[SecretsCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:

    /** Capability bound to one named secrets store, single-secret reads only. */
    @scala.caps.assumeSafe
    trait ReadOnly extends scala.caps.ExclusiveCapability:
      /** The secrets store this capability is bound to. */
      val storeName: SecretStoreName

      /** Retrieve a single named secret value. Returns `None` if absent.
        *
        * @param metadata
        *   optional metadata passed to the secrets backend
        */
      def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty): Option[SecretValue]

    /** Forwarders for [[SecretsCapability.Instance.ReadOnly]]. */
    @scala.caps.assumeSafe
    object ReadOnly:
      def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
          cap: ReadOnly,
      ): Option[SecretValue] =
        cap.get(key, metadata)

    def get(key: SecretKey, metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
        cap: Instance,
    ): Option[SecretValue] =
      cap.get(key, metadata)
    def getBulk(metadata: Map[MetadataKey, MetadataValue] = Map.empty)(using
        cap: Instance,
    ): Map[SecretKey, SecretValue] =
      cap.getBulk(metadata)

  /** Narrow the universal capability in scope to one store and return it. */
  def instance(storeName: SecretStoreName)(using cap: SecretsCapability): Instance^{cap} =
    cap.instance(storeName)
  def get(
      storeName: SecretStoreName,
      key: SecretKey,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using cap: SecretsCapability): Option[SecretValue] =
    cap.get(storeName, key, metadata)
  def getBulk(
      storeName: SecretStoreName,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  )(using cap: SecretsCapability): Map[SecretKey, SecretValue] =
    cap.getBulk(storeName, metadata)
