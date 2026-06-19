package dapr4s.state

import dapr4s.*

import language.experimental.safe

/** Result of a state fetch that also exposes the server-side ETag. */
final case class StateEntry[T](value: Option[T], etag: Option[ETag])

/** Base type for operations in a [[StateCapability.transaction]] call.
  *
  * All-or-nothing: if any operation fails the transaction the entire batch is rolled back. Use the smart constructors
  * [[StateOp.UpsertOp]] and [[StateOp.DeleteOp]].
  */
sealed abstract class StateOp

object StateOp:
  /** Upsert a key with a pre-encoded JSON value and an optional ETag.
    *
    * Values are encoded at construction time to avoid type erasure issues when the operation is processed in
    * [[StateCapability.transaction]]. Use the companion `apply[T]` smart constructor to encode a typed value.
    */
  final case class UpsertOp(key: StateStoreKey, encodedValue: SerializedJson, etag: Option[ETag]) extends StateOp

  object UpsertOp:
    /** Smart constructor that encodes `value` immediately using its [[JsonCodec]]. */
    def apply[T: JsonCodec](key: StateStoreKey, value: T, etag: Option[ETag] = None): UpsertOp =
      new UpsertOp(key, SerializedJson(summon[JsonCodec[T]].encode(value)), etag)

  /** Delete a key with an optional ETag for optimistic concurrency. */
  final case class DeleteOp(key: StateStoreKey, etag: Option[ETag] = None) extends StateOp
