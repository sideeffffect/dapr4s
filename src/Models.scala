package dapr.safe

// ---------------------------------------------------------------------------
// Opaque domain types — prevent accidental misuse (e.g. StoreName vs PubSubName)
// ---------------------------------------------------------------------------

opaque type StoreName = String
object StoreName:
  def apply(s: String): StoreName =
    require(s.nonEmpty, "StoreName must not be empty")
    s
  extension (n: StoreName) def value: String = n

opaque type PubSubName = String
object PubSubName:
  def apply(s: String): PubSubName =
    require(s.nonEmpty, "PubSubName must not be empty")
    s
  extension (n: PubSubName) def value: String = n

opaque type Topic = String
object Topic:
  def apply(s: String): Topic =
    require(s.nonEmpty, "Topic must not be empty")
    s
  extension (n: Topic) def value: String = n

opaque type AppId = String
object AppId:
  def apply(s: String): AppId =
    require(s.nonEmpty, "AppId must not be empty")
    s
  extension (n: AppId) def value: String = n

opaque type SecretStoreName = String
object SecretStoreName:
  def apply(s: String): SecretStoreName =
    require(s.nonEmpty, "SecretStoreName must not be empty")
    s
  extension (n: SecretStoreName) def value: String = n

opaque type ConfigStoreName = String
object ConfigStoreName:
  def apply(s: String): ConfigStoreName =
    require(s.nonEmpty, "ConfigStoreName must not be empty")
    s
  extension (n: ConfigStoreName) def value: String = n

opaque type BindingName = String
object BindingName:
  def apply(s: String): BindingName =
    require(s.nonEmpty, "BindingName must not be empty")
    s
  extension (n: BindingName) def value: String = n

opaque type ETag = String
object ETag:
  def apply(s: String): ETag = s
  extension (n: ETag) def value: String = n

// ---------------------------------------------------------------------------
// Value types
// ---------------------------------------------------------------------------

/** Result of a state fetch that also exposes the server-side ETag. */
case class StateEntry[T](value: Option[T], etag: Option[ETag])

/** A single item returned by the configuration API. */
case class ConfigItem(
    key: String,
    value: String,
    version: String,
    metadata: Map[String, String] = Map.empty
)

// ---------------------------------------------------------------------------
// State transaction operations
// ---------------------------------------------------------------------------

sealed trait StateOp

object StateOp:
  /** Upsert a key with a pre-encoded JSON value and an optional ETag.
    *
    * Values are encoded at construction time to avoid type erasure issues
    * when the operation is processed in [[StateCapability.transaction]].
    * Use the companion `apply[T]` smart constructor to encode a typed value.
    */
  case class UpsertOp(key: String, encodedValue: String, etag: Option[ETag]) extends StateOp

  object UpsertOp:
    /** Smart constructor that encodes `value` immediately using its [[JsonCodec]]. */
    def apply[T: JsonCodec](key: String, value: T, etag: Option[ETag] = None): UpsertOp =
      new UpsertOp(key, summon[JsonCodec[T]].encode(value), etag)

  /** Delete a key with an optional ETag for optimistic concurrency. */
  case class DeleteOp(key: String, etag: Option[ETag] = None) extends StateOp

// ---------------------------------------------------------------------------
// Exceptions
// ---------------------------------------------------------------------------

class DaprException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

class ETagMismatchException(key: String, etag: ETag)
    extends DaprException(
      s"ETag mismatch for key '$key': expected etag '${etag.value}'",
      null
    )
