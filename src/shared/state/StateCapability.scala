package dapr4s.state

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Accessor (rung 2) for state stores: an "any store" handle obtained argument-less via [[DaprCapability.state]], whose
  * [[apply]] narrows to a [[StateCapability]] bound to one store. Hold and pass this around to delegate "may reach any
  * state store"; `dapr.state(name)` reads identically (it is `dapr.state.apply(name)`).
  */
@scala.caps.assumeSafe
trait AccessStateCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[StateCapability]] for the named state store. */
  def apply(storeName: StateStoreName): StateCapability^{this}

/** Capability for DAPR state management operations against a named store.
  *
  * Acquired via [[DaprCapability.state]].
  */
@scala.caps.assumeSafe
trait StateCapability extends scala.caps.ExclusiveCapability:
  val storeName: StateStoreName

  /** Fetch a value; returns `None` if the key does not exist.
    *
    * @param consistency
    *   read consistency level; [[StateConsistency.Default]] uses the store's own default
    */
  def get[T: JsonCodec](key: StateStoreKey, consistency: StateConsistency = StateConsistency.Default): Option[T]

  /** Fetch a value together with the current server-side ETag.
    *
    * @param consistency
    *   read consistency level; [[StateConsistency.Default]] uses the store's own default
    */
  def getWithETag[T: JsonCodec](key: StateStoreKey, consistency: StateConsistency = StateConsistency.Default): StateEntry[T]

  /** Fetch multiple values by key in a single call. */
  def getBulk[T: JsonCodec](keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]]

  /** Unconditionally save a value. */
  def save[T: JsonCodec](key: StateStoreKey, value: T): Unit

  /** Save multiple key-value pairs in a single call. */
  def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)]): Unit

  /** Save a value only if the provided ETag matches the server-side ETag. Returns `None` on success, `Some(e)` if the
    * ETag did not match.
    *
    * @param metadata
    *   optional metadata forwarded to the state store
    * @param consistency
    *   write consistency level; [[StateConsistency.Default]] uses the store's own default
    * @param concurrency
    *   concurrency control; [[StateConcurrency.FirstWrite]] is the typical safe default for optimistic locking
    */
  def saveWithETag[T: JsonCodec](
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException]

  /** Unconditionally delete a key (no-op if the key is absent). */
  def delete(key: StateStoreKey): Unit

  /** Delete a key only if the provided ETag matches. Returns `None` on success, `Some(e)` if the ETag did not match.
    *
    * @param consistency
    *   write consistency level; [[StateConsistency.Default]] uses the store's own default
    * @param concurrency
    *   concurrency control; [[StateConcurrency.FirstWrite]] is the typical safe default for optimistic locking
    */
  def deleteWithETag(
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException]

  /** Execute multiple state operations atomically (all-or-nothing). */
  def transaction(ops: Seq[StateOp]): Unit

  /** Query state using a filter expression. */
  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]]

/** Companion-object API for [[StateCapability]].
  *
  * Each method forwards to the `StateCapability` provided by the enclosing `using` context, so callers never need to
  * name the capability:
  * {{{
  *   def myHandler(key: StateStoreKey)(using StateCapability): String throws Exception =
  *     StateCapability.get[String](key).getOrElse("default")
  * }}}
  */
@scala.caps.assumeSafe
object StateCapability:
  def get[T: JsonCodec](
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  )(using cap: StateCapability): Option[T] =
    cap.get(key, consistency)
  def getWithETag[T: JsonCodec](
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  )(using cap: StateCapability): StateEntry[T] =
    cap.getWithETag(key, consistency)
  def getBulk[T: JsonCodec](keys: Seq[StateStoreKey])(using
      cap: StateCapability,
  ): Map[StateStoreKey, StateEntry[T]] =
    cap.getBulk(keys)
  def save[T: JsonCodec](key: StateStoreKey, value: T)(using cap: StateCapability): Unit =
    cap.save(key, value)
  def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)])(using cap: StateCapability): Unit =
    cap.saveBulk(entries)
  def saveWithETag[T: JsonCodec](
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  )(using cap: StateCapability): Option[ETagMismatchException] =
    cap.saveWithETag(key, value, etag, metadata, consistency, concurrency)
  def delete(key: StateStoreKey)(using cap: StateCapability): Unit =
    cap.delete(key)
  def deleteWithETag(
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  )(using cap: StateCapability): Option[ETagMismatchException] =
    cap.deleteWithETag(key, etag, consistency, concurrency)
  def transaction(ops: Seq[StateOp])(using cap: StateCapability): Unit =
    cap.transaction(ops)
  def queryState[T: JsonCodec](query: StateQuery)(using
      cap: StateCapability,
  ): List[StateEntry[T]] =
    cap.queryState(query)

