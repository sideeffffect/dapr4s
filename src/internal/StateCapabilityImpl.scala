package dapr4s.internal

import dapr4s.*
import io.dapr.client.domain.{State as DaprState, StateOptions, TransactionalStateOperation}
import io.dapr.client.domain.TransactionalStateOperation.OperationType

import scala.jdk.CollectionConverters.*
import MonoOps.*
import NullOps.*

private object StateOpsConversions:
  def toJavaConsistency(c: StateConsistency): StateOptions.Consistency | Null =
    c match
      case StateConsistency.Default  => null
      case StateConsistency.Eventual => StateOptions.Consistency.EVENTUAL
      case StateConsistency.Strong   => StateOptions.Consistency.STRONG

  def toJavaConcurrency(c: StateConcurrency): StateOptions.Concurrency | Null =
    c match
      case StateConcurrency.Default    => null
      case StateConcurrency.FirstWrite => StateOptions.Concurrency.FIRST_WRITE
      case StateConcurrency.LastWrite  => StateOptions.Concurrency.LAST_WRITE

@scala.caps.assumeSafe
private[dapr4s] final class StateCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: StoreName,
) extends StateCapability:

  import StateOpsConversions.*

  def get[T: JsonCodec](key: StateKey, consistency: StateConsistency = StateConsistency.Default): Option[T] =
    val mono =
      if consistency == StateConsistency.Default then scope.client.getState(storeName.value, key.value, classOf[String])
      else
        scope.client.getState(
          storeName.value,
          key.value,
          new StateOptions(toJavaConsistency(consistency), null),
          classOf[String],
        )
    mono.awaitResult().toOption.flatMap(s => s.getValue.toOption.filterNot(_.isEmpty)).map(decode[T])

  def getWithETag[T: JsonCodec](
      key: StateKey,
      consistency: StateConsistency = StateConsistency.Default,
  ): StateEntry[T] =
    val mono =
      if consistency == StateConsistency.Default then scope.client.getState(storeName.value, key.value, classOf[String])
      else
        scope.client.getState(
          storeName.value,
          key.value,
          new StateOptions(toJavaConsistency(consistency), null),
          classOf[String],
        )
    mono
      .awaitResult()
      .toOption
      .fold(StateEntry[T](None, None)) { state =>
        val raw = state.getValue.toOption.filterNot(_.isEmpty)
        val etag = state.getEtag.toOption.map(ETag(_))
        StateEntry(raw.map(decode[T]), etag)
      }

  def getBulk[T: JsonCodec](keys: Seq[StateKey]): Map[StateKey, StateEntry[T]] =
    if keys.isEmpty then Map.empty
    else
      val javaKeys: java.util.List[String] = keys.map(_.value).asJava
      scope.client
        .getBulkState(storeName.value, javaKeys, classOf[String])
        .awaitResult()
        .toOption
        .fold(keys.map(k => k -> StateEntry[T](None, None)).toMap) { results =>
          results.asScala.map { state =>
            val key = StateKey(state.getKey.nn)
            val raw = state.getValue.toOption.filterNot(_.isEmpty)
            val etag = state.getEtag.toOption.map(ETag(_))
            key -> StateEntry(raw.map(decode[T]), etag)
          }.toMap
        }

  def save[T: JsonCodec](key: StateKey, value: T): Unit =
    val json = summon[JsonCodec[T]].encode(value)
    scope.client.saveState(storeName.value, key.value, json).awaitResult(): Unit

  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)]): Unit =
    if entries.nonEmpty then
      val states: java.util.List[DaprState[?]] = entries.map { case (key, value) =>
        val json = summon[JsonCodec[T]].encode(value)
        new DaprState[String](key.value, json, null, null)
      }.asJava
      scope.client.saveBulkState(storeName.value, states).awaitResult(): Unit

  def saveWithETag[T: JsonCodec](
      key: StateKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    val json = summon[JsonCodec[T]].encode(value)
    val opts = new StateOptions(toJavaConsistency(consistency), toJavaConcurrency(concurrency))
    val javaMeta: java.util.Map[String, String] = metadata.map { case (k, v) => k.value -> v.value }.asJava
    try
      scope.client.saveState(storeName.value, key.value, etag.value, json, javaMeta, opts).awaitResult(): Unit
      None
    catch case e: io.dapr.exceptions.DaprException if isETagConflict(e) => Some(ETagMismatchException(key, etag))

  def delete(key: StateKey): Unit =
    scope.client.deleteState(storeName.value, key.value).awaitResult(): Unit

  def deleteWithETag(
      key: StateKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    val opts = new StateOptions(toJavaConsistency(consistency), toJavaConcurrency(concurrency))
    try
      scope.client.deleteState(storeName.value, key.value, etag.value, opts).awaitResult(): Unit
      None
    catch case e: io.dapr.exceptions.DaprException if isETagConflict(e) => Some(ETagMismatchException(key, etag))

  def transaction(ops: Seq[StateOp]): Unit =
    val javaOps: java.util.List[TransactionalStateOperation[?]] =
      ops.map(toJavaOp).asJava
    scope.client
      .executeStateTransaction(storeName.value, javaOps)
      .awaitResult(): Unit

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] =
    scope.clientPreview
      .queryState(storeName.value, query.value, classOf[String])
      .awaitResult()
      .toOption
      .flatMap(r => r.getResults.toOption)
      .fold(List.empty[StateEntry[T]]) { items =>
        items.asScala.toList.map { item =>
          val raw = item.getValue.toOption.filterNot(_.isEmpty)
          val etag = item.getEtag.toOption.map(ETag(_))
          StateEntry(raw.map(decode[T]), etag)
        }
      }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def decode[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)

  private def toJavaOp(op: StateOp): TransactionalStateOperation[?] =
    op match
      case StateOp.UpsertOp(key, encodedValue, etag) =>
        val daprState = etag match
          case Some(e) => new DaprState[String](key.value, encodedValue.value, e.value, null)
          case None    => new DaprState[String](key.value, encodedValue.value, null, null)
        new TransactionalStateOperation[String](OperationType.UPSERT, daprState)

      case StateOp.DeleteOp(key, etag) =>
        val daprState = etag match
          case Some(e) => new DaprState[String](key.value, null, e.value, null)
          case None    => new DaprState[String](key.value, null, null, null)
        new TransactionalStateOperation[String](OperationType.DELETE, daprState)

  private def isETagConflict(e: io.dapr.exceptions.DaprException): Boolean =
    e.getHttpStatusCode == 409 || e.getMessage.toOption.exists(_.contains("ABORTED"))
