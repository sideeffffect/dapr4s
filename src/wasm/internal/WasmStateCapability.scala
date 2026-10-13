package dapr4s.internal

import java.nio.charset.StandardCharsets.UTF_8

import scala.scalajs.wit

import dapr4s.*
import dapr4s.state.*
import dapr4swit.dapr_wasm_components.interfaces.{state as witState, state_read}

/** dapr4s's `StateCapability` over the WIT `state-read` / `state` imports. It depends on the GENERATED plain import
  * traits (`state_read.StateReadImports`, `state.StateImports`, see wasm/project/WitGlue.scala), not on the import
  * functions: `WasmRuntime` passes their live objects (compiled by the WIT-aware 2.13 compiler, which is the only one
  * that can lower an import call), and the unit tests (test/wasm/unit) pass fakes.
  *
  * Mapping (the `wasm` wall rules of docs/WASM-COMPONENTS-PROPOSAL.md §2c; errors land in the `DaprException` family
  * of src/shared/Exceptions.scala like on the JVM/JS):
  *   - `get-error.key-not-found`            -> `None` / empty `StateEntry`
  *   - `write-error.etag-mismatch`          -> `ETagMismatchException` (returned by the *WithETag variants)
  *   - `state-error.store-not-found(name)`  -> `ComponentNotFoundException(State, name)`
  *   - `state-error.permission-denied(msg)` -> `PermissionDeniedException(msg)`
  *   - values are the JsonCodec JSON text as UTF-8 bytes (the HTTP provider stores JSON as-is)
  *
  * WHY @assumeSafe (on this object and the two classes below): same contract as the JVM/JS walls
  * (src/{jvm,js}/internal/StateCapabilityImpl.scala). They are the boundary to the platform SDK — here the generated
  * WIT bindings and the generated import traits, plain Scala 2.13 classes that CC cannot check. They hold no
  * capability but the component's WIT imports (behind those traits) and never leak a binding type into the public API.
  */
@scala.caps.assumeSafe
private object WasmState:
  type Metadata = Array[wit.Tuple2[String, String]]
  val noMetadata: Metadata = Array()

  def stateError(storeName: StateStoreName, e: state_read.StateError): DaprException =
    e match
      case nf: state_read.StateError.StoreNotFound =>
        // The wasi-http provider (0.7.0) puts daprd's raw error body into the payload, not the
        // store name, so the name comes from the call and the payload becomes the detail.
        ComponentNotFoundException(DaprComponentKind.State, storeName.value, detail = nf.value)
      case pd: state_read.StateError.PermissionDenied => PermissionDeniedException(pd.value)

  def consistency(c: StateConsistency): wit.Option[state_read.Consistency] =
    c match
      case StateConsistency.Default  => wit.None
      case StateConsistency.Eventual => wit.Some(state_read.Consistency.Eventual)
      case StateConsistency.Strong   => wit.Some(state_read.Consistency.Strong)

  def options(c: StateConsistency, k: StateConcurrency): wit.Option[state_read.StateOptions] =
    if c == StateConsistency.Default && k == StateConcurrency.Default then wit.None
    else
      val cons = c match
        case StateConsistency.Default  => state_read.Consistency.Unspecified
        case StateConsistency.Eventual => state_read.Consistency.Eventual
        case StateConsistency.Strong   => state_read.Consistency.Strong
      val conc = k match
        case StateConcurrency.Default    => state_read.Concurrency.Unspecified
        case StateConcurrency.FirstWrite => state_read.Concurrency.FirstWrite
        case StateConcurrency.LastWrite  => state_read.Concurrency.LastWrite
      wit.Some(state_read.StateOptions(conc, cons))

  def toBytes(json: String): Array[Byte] = json.getBytes(UTF_8)

  def decode[T: JsonCodec](bytes: Array[Byte]): Option[T] =
    if bytes.isEmpty then None else Some(JsonCodec.decodeOrThrow[T](new String(bytes, UTF_8)))

  def etagOf(o: wit.Option[String]): Option[ETag] = o.toScalaOption.map(ETag(_))

  def witOption[A](o: Option[A]): wit.Option[A] = o.fold[wit.Option[A]](wit.None)(a => wit.Some(a))

@scala.caps.assumeSafe
final class WasmStateInstance(
    val storeName: StateStoreName,
    reads: state_read.StateReadImports,
    writes: witState.StateImports,
) extends StateCapability.Instance:
  import WasmState.*

  def get[T: JsonCodec](key: StateStoreKey, consistency: StateConsistency = StateConsistency.Default): Option[T] =
    getWithETag[T](key, consistency).value

  def getWithETag[T: JsonCodec](
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  ): StateEntry[T] =
    reads
      .get(storeName.value, key.value, WasmState.consistency(consistency), noMetadata)
      .fold(
        ok => StateEntry(decode[T](ok.data), etagOf(ok.etag)),
        {
          case state_read.GetError.KeyNotFound => StateEntry[T](None, None)
          case s: state_read.GetError.State    => throw stateError(storeName, s.value)
        },
      )

  def getBulk[T: JsonCodec](keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]] =
    if keys.isEmpty then Map.empty
    else
      reads
        .getBulk(storeName.value, keys.map(_.value).toArray, wit.None, noMetadata)
        .fold(
          items =>
            items.toList.map { item =>
              item.error.toScalaOption.foreach(msg => throw DaprApiException("ERR_STATE_GET", msg))
              StateStoreKey(item.key) -> StateEntry(decode[T](item.value), etagOf(item.etag))
            }.toMap,
          err => throw stateError(storeName, err),
        )

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] =
    reads
      .query(storeName.value, query.value, noMetadata)
      .fold(
        resp => resp.items.toList.map(i => StateEntry(decode[T](i.data), etagOf(i.etag))),
        {
          case state_read.QueryError.NotQueryable =>
            throw DaprApiException("ERR_NOT_SUPPORTED_STATE_OPERATION", s"state store ${storeName.value} is not queryable")
          case iq: state_read.QueryError.InvalidQuery => throw DaprApiException("ERR_MALFORMED_REQUEST", iq.value)
          case s: state_read.QueryError.State         => throw stateError(storeName, s.value)
        },
      )

  private def write(
      key: StateStoreKey,
      json: String,
      etag: Option[ETag],
      metadata: Map[MetadataKey, MetadataValue],
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    val md: Metadata = metadata.toArray.map((k, v) => wit.Tuple2(k.value, v.value))
    val item = state_read.StateItem(
      key.value,
      toBytes(json),
      witOption(etag.map(_.value)),
      md,
      options(consistency, concurrency),
    )
    writes
      .save(storeName.value, Array(item), noMetadata)
      .fold(
        _ => None,
        {
          case _: witState.WriteError.EtagMismatch => Some(ETagMismatchException(key, etag.getOrElse(ETag(""))))
          case s: witState.WriteError.State        => throw stateError(storeName, s.value)
        },
      )

  def save[T: JsonCodec](key: StateStoreKey, value: T): Unit =
    write(key, summon[JsonCodec[T]].encode(value), None, Map.empty, StateConsistency.Default, StateConcurrency.Default)
      .foreach(e => throw e)

  def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)]): Unit =
    val items = entries.toArray.map { (k, v) =>
      state_read.StateItem(k.value, toBytes(summon[JsonCodec[T]].encode(v)), wit.None, noMetadata, wit.None)
    }
    writes
      .save(storeName.value, items, noMetadata)
      .fold(
        _ => (),
        {
          case m: witState.WriteError.EtagMismatch =>
            throw DaprApiException("ERR_STATE_SAVE", s"etag mismatch ${m.value.toScalaOption.getOrElse("")}")
          case s: witState.WriteError.State => throw stateError(storeName, s.value)
        },
      )

  def saveWithETag[T: JsonCodec](
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    write(key, summon[JsonCodec[T]].encode(value), Some(etag), metadata, consistency, concurrency)

  private def remove(
      key: StateStoreKey,
      etag: Option[ETag],
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    writes
      .delete(storeName.value, key.value, witOption(etag.map(_.value)), options(consistency, concurrency), noMetadata)
      .fold(
        _ => None,
        {
          case _: witState.WriteError.EtagMismatch => Some(ETagMismatchException(key, etag.getOrElse(ETag(""))))
          case s: witState.WriteError.State        => throw stateError(storeName, s.value)
        },
      )

  def delete(key: StateStoreKey): Unit =
    remove(key, None, StateConsistency.Default, StateConcurrency.Default).foreach(e => throw e)

  def deleteWithETag(
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    remove(key, Some(etag), consistency, concurrency)

  def transaction(ops: Seq[StateOp]): Unit =
    val requests = ops.toArray.map {
      case StateOp.UpsertOp(key, encoded, etag) =>
        witState.TransactionRequest(
          witState.TransactionOperation.Upsert,
          key.value,
          wit.Some(toBytes(encoded.value)),
          witOption(etag.map(_.value)),
          noMetadata,
          wit.None,
        )
      case StateOp.DeleteOp(key, etag) =>
        witState.TransactionRequest(
          witState.TransactionOperation.Delete,
          key.value,
          wit.None,
          witOption(etag.map(_.value)),
          noMetadata,
          wit.None,
        )
    }
    writes
      .executeTransaction(storeName.value, requests, noMetadata)
      .fold(
        _ => (),
        {
          case m: witState.TransactionError.EtagMismatch =>
            throw DaprApiException("ERR_STATE_TRANSACTION", s"etag mismatch ${m.value.toScalaOption.getOrElse("")}")
          case witState.TransactionError.NotTransactional =>
            throw DaprApiException("ERR_STATE_STORE_NOT_SUPPORTED", s"state store ${storeName.value} is not transactional")
          case s: witState.TransactionError.State => throw stateError(storeName, s.value)
        },
      )

/** The universal (name-first) tier: every call narrows to a per-store [[WasmStateInstance]]. */
@scala.caps.assumeSafe
final class WasmStateCapability(reads: state_read.StateReadImports, writes: witState.StateImports) extends StateCapability:
  // WHAT: `asInstanceOf` drops the fresh capture set of the newly constructed instance.
  // WHY: CC types `new WasmStateInstance(...)` as `^{any created in method instance}`, which cannot
  //   flow into the declared `^{this}`; dapr4s's JVM/JS walls do the identical cast
  //   (internal/DaprCapabilityImpl.scala, `new StateCapabilityImpl(scope, storeName).asInstanceOf`).
  // WHY SAFE: the instance holds nothing but the store name and the same two import traits as `this`
  //   (the component's WIT imports, whose lifetime is the component instance's), i.e. it is no more
  //   powerful than `this`.
  // WHERE: src/jvm/internal/DaprCapabilityImpl.scala (`state` / `instance`), same cast and rationale.
  override def instance(storeName: StateStoreName): StateCapability.Instance^{this} =
    WasmStateInstance(storeName, reads, writes).asInstanceOf[StateCapability.Instance]

  def get[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, consistency: StateConsistency): Option[T] =
    WasmStateInstance(storeName, reads, writes).get[T](key, consistency)
  def getWithETag[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, consistency: StateConsistency)
      : StateEntry[T] =
    WasmStateInstance(storeName, reads, writes).getWithETag[T](key, consistency)
  def getBulk[T: JsonCodec](storeName: StateStoreName, keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]] =
    WasmStateInstance(storeName, reads, writes).getBulk[T](keys)
  def queryState[T: JsonCodec](storeName: StateStoreName, query: StateQuery): List[StateEntry[T]] =
    WasmStateInstance(storeName, reads, writes).queryState[T](query)
  def save[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, value: T): Unit =
    WasmStateInstance(storeName, reads, writes).save(key, value)
  def saveBulk[T: JsonCodec](storeName: StateStoreName, entries: Seq[(StateStoreKey, T)]): Unit =
    WasmStateInstance(storeName, reads, writes).saveBulk(entries)
  def saveWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue],
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    WasmStateInstance(storeName, reads, writes).saveWithETag(key, value, etag, metadata, consistency, concurrency)
  def delete(storeName: StateStoreName, key: StateStoreKey): Unit =
    WasmStateInstance(storeName, reads, writes).delete(key)
  def deleteWithETag(
      storeName: StateStoreName,
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    WasmStateInstance(storeName, reads, writes).deleteWithETag(key, etag, consistency, concurrency)
  def transaction(storeName: StateStoreName, ops: Seq[StateOp]): Unit =
    WasmStateInstance(storeName, reads, writes).transaction(ops)
