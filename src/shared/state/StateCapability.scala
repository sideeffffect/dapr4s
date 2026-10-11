package dapr4s.state

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration

/** Universal capability for DAPR state management: reaches **any** state store, so every operation names its store.
  *
  * Obtained via [[DaprCapability.state]]. The capability family has four interfaces, all in this companion:
  *
  *   - [[StateCapability]] — any store, full authority (this trait)
  *   - [[StateCapability.ReadOnly]] — any store, read-only (`get`, `getWithETag`, `getBulk`, `queryState`)
  *   - [[StateCapability.Instance]] — one store, full authority
  *   - [[StateCapability.Instance.ReadOnly]] — one store, read-only
  *
  * Narrow by store with [[instance]] (`state.instance(storeName)`), and by authority by upcasting to the `ReadOnly` super-trait.
  * The two narrowings commute: `instance` on a [[StateCapability.ReadOnly]] yields an [[StateCapability.Instance.ReadOnly]],
  * so handing someone a read-only universal capability cannot be widened back by narrowing to a store.
  */
@scala.caps.assumeSafe
trait StateCapability extends StateCapability.ReadOnly:
  /** Narrow to the named state store, keeping full authority. */
  override def instance(storeName: StateStoreName): StateCapability.Instance^{this}

  /** Unconditionally save a value. */
  def save[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, value: T): Unit

  /** Save multiple key-value pairs in a single call. */
  def saveBulk[T: JsonCodec](storeName: StateStoreName, entries: Seq[(StateStoreKey, T)]): Unit

  /** Save a value only if the provided ETag matches; see [[StateCapability.Instance.saveWithETag]]. */
  def saveWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException]

  /** Unconditionally delete a key (no-op if the key is absent). */
  def delete(storeName: StateStoreName, key: StateStoreKey): Unit

  /** Delete a key only if the provided ETag matches; see [[StateCapability.Instance.deleteWithETag]]. */
  def deleteWithETag(
      storeName: StateStoreName,
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException]

  /** Execute multiple state operations atomically (all-or-nothing) against one store. */
  def transaction(storeName: StateStoreName, ops: Seq[StateOp]): Unit

/** The [[StateCapability]] family: the read-only universal view, the store-bound instance views, and the
  * companion-object forwarders for each.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def lookup(key: StateStoreKey)(using StateCapability.Instance.ReadOnly): Option[String] =
  *     StateCapability.Instance.ReadOnly.get[String](key)
  * }}}
  */
@scala.caps.assumeSafe
object StateCapability:

  /** Read-only view of [[StateCapability]]: reaches any store, but only reads. */
  @scala.caps.assumeSafe
  trait ReadOnly extends scala.caps.ExclusiveCapability:
    /** Narrow to the named state store, keeping read-only authority. */
    def instance(storeName: StateStoreName): Instance.ReadOnly^{this}

    /** Fetch a value; returns `None` if the key does not exist. */
    def get[T: JsonCodec](
        storeName: StateStoreName,
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    ): Option[T]

    /** Fetch a value together with the current server-side ETag. */
    def getWithETag[T: JsonCodec](
        storeName: StateStoreName,
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    ): StateEntry[T]

    /** Fetch multiple values by key in a single call. */
    def getBulk[T: JsonCodec](storeName: StateStoreName, keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]]

    /** Query state using a filter expression. */
    def queryState[T: JsonCodec](storeName: StateStoreName, query: StateQuery): List[StateEntry[T]]

  /** Forwarders for [[StateCapability.ReadOnly]]. */
  @scala.caps.assumeSafe
  object ReadOnly:
    /** Narrow the read-only capability in scope to one store and return it. */
    def instance(storeName: StateStoreName)(using cap: ReadOnly): Instance.ReadOnly^{cap} =
      cap.instance(storeName)
    def get[T: JsonCodec](
        storeName: StateStoreName,
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    )(using cap: ReadOnly): Option[T] =
      cap.get(storeName, key, consistency)
    def getWithETag[T: JsonCodec](
        storeName: StateStoreName,
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    )(using cap: ReadOnly): StateEntry[T] =
      cap.getWithETag(storeName, key, consistency)
    def getBulk[T: JsonCodec](storeName: StateStoreName, keys: Seq[StateStoreKey])(using
        cap: ReadOnly,
    ): Map[StateStoreKey, StateEntry[T]] =
      cap.getBulk(storeName, keys)
    def queryState[T: JsonCodec](storeName: StateStoreName, query: StateQuery)(using
        cap: ReadOnly,
    ): List[StateEntry[T]] =
      cap.queryState(storeName, query)

  /** Capability bound to one named state store, full authority. Obtained via `state.instance(storeName)`. */
  @scala.caps.assumeSafe
  trait Instance extends Instance.ReadOnly:
    /** Unconditionally save a value. */
    def save[T: JsonCodec](key: StateStoreKey, value: T): Unit

    /** Save multiple key-value pairs in a single call. */
    def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)]): Unit

    /** Save a value only if the provided ETag matches the server-side ETag. Returns `None` on success, `Some(e)` if
      * the ETag did not match.
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

  /** [[StateCapability.Instance]]'s read-only view, and the forwarders for [[StateCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:

    /** Capability bound to one named state store, read-only. */
    @scala.caps.assumeSafe
    trait ReadOnly extends scala.caps.ExclusiveCapability:
      /** The state store this capability is bound to. */
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
      def getWithETag[T: JsonCodec](
          key: StateStoreKey,
          consistency: StateConsistency = StateConsistency.Default,
      ): StateEntry[T]

      /** Fetch multiple values by key in a single call. */
      def getBulk[T: JsonCodec](keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]]

      /** Query state using a filter expression. */
      def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]]

    /** Forwarders for [[StateCapability.Instance.ReadOnly]]. */
    @scala.caps.assumeSafe
    object ReadOnly:
      def get[T: JsonCodec](
          key: StateStoreKey,
          consistency: StateConsistency = StateConsistency.Default,
      )(using cap: ReadOnly): Option[T] =
        cap.get(key, consistency)
      def getWithETag[T: JsonCodec](
          key: StateStoreKey,
          consistency: StateConsistency = StateConsistency.Default,
      )(using cap: ReadOnly): StateEntry[T] =
        cap.getWithETag(key, consistency)
      def getBulk[T: JsonCodec](keys: Seq[StateStoreKey])(using
          cap: ReadOnly,
      ): Map[StateStoreKey, StateEntry[T]] =
        cap.getBulk(keys)
      def queryState[T: JsonCodec](query: StateQuery)(using cap: ReadOnly): List[StateEntry[T]] =
        cap.queryState(query)

    def get[T: JsonCodec](
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    )(using cap: Instance): Option[T] =
      cap.get(key, consistency)
    def getWithETag[T: JsonCodec](
        key: StateStoreKey,
        consistency: StateConsistency = StateConsistency.Default,
    )(using cap: Instance): StateEntry[T] =
      cap.getWithETag(key, consistency)
    def getBulk[T: JsonCodec](keys: Seq[StateStoreKey])(using
        cap: Instance,
    ): Map[StateStoreKey, StateEntry[T]] =
      cap.getBulk(keys)
    def queryState[T: JsonCodec](query: StateQuery)(using cap: Instance): List[StateEntry[T]] =
      cap.queryState(query)
    def save[T: JsonCodec](key: StateStoreKey, value: T)(using cap: Instance): Unit =
      cap.save(key, value)
    def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)])(using cap: Instance): Unit =
      cap.saveBulk(entries)
    def saveWithETag[T: JsonCodec](
        key: StateStoreKey,
        value: T,
        etag: ETag,
        metadata: Map[MetadataKey, MetadataValue] = Map.empty,
        consistency: StateConsistency = StateConsistency.Default,
        concurrency: StateConcurrency = StateConcurrency.FirstWrite,
    )(using cap: Instance): Option[ETagMismatchException] =
      cap.saveWithETag(key, value, etag, metadata, consistency, concurrency)
    def delete(key: StateStoreKey)(using cap: Instance): Unit =
      cap.delete(key)
    def deleteWithETag(
        key: StateStoreKey,
        etag: ETag,
        consistency: StateConsistency = StateConsistency.Default,
        concurrency: StateConcurrency = StateConcurrency.FirstWrite,
    )(using cap: Instance): Option[ETagMismatchException] =
      cap.deleteWithETag(key, etag, consistency, concurrency)
    def transaction(ops: Seq[StateOp])(using cap: Instance): Unit =
      cap.transaction(ops)

  /** Narrow the universal capability in scope to one store and return it. */
  def instance(storeName: StateStoreName)(using cap: StateCapability): Instance^{cap} =
    cap.instance(storeName)
  def get[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  )(using cap: StateCapability): Option[T] =
    cap.get(storeName, key, consistency)
  def getWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  )(using cap: StateCapability): StateEntry[T] =
    cap.getWithETag(storeName, key, consistency)
  def getBulk[T: JsonCodec](storeName: StateStoreName, keys: Seq[StateStoreKey])(using
      cap: StateCapability,
  ): Map[StateStoreKey, StateEntry[T]] =
    cap.getBulk(storeName, keys)
  def queryState[T: JsonCodec](storeName: StateStoreName, query: StateQuery)(using
      cap: StateCapability,
  ): List[StateEntry[T]] =
    cap.queryState(storeName, query)
  def save[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, value: T)(using cap: StateCapability): Unit =
    cap.save(storeName, key, value)
  def saveBulk[T: JsonCodec](storeName: StateStoreName, entries: Seq[(StateStoreKey, T)])(using
      cap: StateCapability,
  ): Unit =
    cap.saveBulk(storeName, entries)
  def saveWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  )(using cap: StateCapability): Option[ETagMismatchException] =
    cap.saveWithETag(storeName, key, value, etag, metadata, consistency, concurrency)
  def delete(storeName: StateStoreName, key: StateStoreKey)(using cap: StateCapability): Unit =
    cap.delete(storeName, key)
  def deleteWithETag(
      storeName: StateStoreName,
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  )(using cap: StateCapability): Option[ETagMismatchException] =
    cap.deleteWithETag(storeName, key, etag, consistency, concurrency)
  def transaction(storeName: StateStoreName, ops: Seq[StateOp])(using cap: StateCapability): Unit =
    cap.transaction(storeName, ops)
