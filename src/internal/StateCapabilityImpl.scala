package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{State as DaprState, StateOptions, TransactionalStateOperation}
import io.dapr.client.domain.TransactionalStateOperation.OperationType
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import scala.jdk.CollectionConverters.*
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class StateCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: StoreName,
) extends StateCapability:

  def get[T: JsonCodec](key: StateKey): Option[T] =
    val state: DaprState[String] | Null =
      scope.client.getState(storeName.value, key.value, classOf[String]).awaitResult()
    if state == null then return None
    val raw: String | Null = state.getValue
    if raw == null || raw.isEmpty then None
    else Some(decode[T](raw))

  def getWithETag[T: JsonCodec](key: StateKey): StateEntry[T] =
    val state: DaprState[String] | Null =
      scope.client.getState(storeName.value, key.value, classOf[String]).awaitResult()
    if state == null then return StateEntry(None, None)
    val raw: String | Null = state.getValue
    val etag: String | Null = state.getEtag
    if raw == null || raw.isEmpty then StateEntry(None, Option(etag.asInstanceOf[String]).map(ETag(_)))
    else
      val decoded = Some(decode[T](raw))
      StateEntry(decoded, Option(etag.asInstanceOf[String]).map(ETag(_)))

  def getBulk[T: JsonCodec](keys: Seq[StateKey]): Map[StateKey, StateEntry[T]] =
    if keys.isEmpty then return Map.empty
    val javaKeys: java.util.List[String] = keys.map(_.value).asJava
    val results: java.util.List[DaprState[String]] | Null =
      scope.client.getBulkState(storeName.value, javaKeys, classOf[String]).awaitResult()
    if results == null then return keys.map(k => k -> StateEntry[T](None, None)).toMap
    results.asScala.map { state =>
      val key = StateKey(state.getKey.nn)
      val raw: String | Null = state.getValue
      val etag: String | Null = state.getEtag
      val entry: StateEntry[T] =
        if raw == null || raw.isEmpty then StateEntry(None, Option(etag.asInstanceOf[String]).map(ETag(_)))
        else
          val decoded = Some(decode[T](raw))
          StateEntry(decoded, Option(etag.asInstanceOf[String]).map(ETag(_)))
      key -> entry
    }.toMap

  def save[T: JsonCodec](key: StateKey, value: T): Unit =
    val json = summon[JsonCodec[T]].encode(value)
    scope.client.saveState(storeName.value, key.value, json).awaitResult(): Unit

  def saveBulk[T: JsonCodec](entries: Seq[(StateKey, T)]): Unit =
    if entries.isEmpty then return
    val states: java.util.List[DaprState[?]] = entries.map { case (key, value) =>
      val json = summon[JsonCodec[T]].encode(value)
      new DaprState[String](key.value, json, null, null)
    }.asJava
    scope.client.saveBulkState(storeName.value, states).awaitResult(): Unit

  def saveWithETag[T: JsonCodec](key: StateKey, value: T, etag: ETag): Unit throws ETagMismatchException =
    val json = summon[JsonCodec[T]].encode(value)
    val opts = new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE)
    try scope.client.saveState(storeName.value, key.value, etag.value, json, opts).awaitResult(): Unit
    catch case e: io.dapr.exceptions.DaprException if isETagConflict(e) => throw ETagMismatchException(key, etag)

  def delete(key: StateKey): Unit =
    scope.client.deleteState(storeName.value, key.value).awaitResult(): Unit

  def deleteWithETag(key: StateKey, etag: ETag): Unit throws ETagMismatchException =
    val opts = new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE)
    try scope.client.deleteState(storeName.value, key.value, etag.value, opts).awaitResult(): Unit
    catch case e: io.dapr.exceptions.DaprException if isETagConflict(e) => throw ETagMismatchException(key, etag)

  def transaction(ops: Seq[StateOp]): Unit =
    val javaOps: java.util.List[TransactionalStateOperation[?]] =
      ops.map(toJavaOp).asJava
    scope.client
      .executeStateTransaction(storeName.value, javaOps)
      .awaitResult(): Unit

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] =
    val previewClient = scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]
    val result: io.dapr.client.domain.QueryStateResponse[String] | Null =
      previewClient.queryState(storeName.value, query.value, classOf[String]).awaitResult()
    if result == null then return List.empty
    val items = result.getResults
    if items == null then return List.empty
    items.asScala.toList.map { item =>
      val raw: String | Null = item.getValue
      val etag: String | Null = item.getEtag
      if raw == null || raw.isEmpty then StateEntry[T](None, Option(etag.asInstanceOf[String]).map(ETag(_)))
      else
        val decoded = Some(decode[T](raw))
        StateEntry(decoded, Option(etag.asInstanceOf[String]).map(ETag(_)))
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
          case Some(e) => new DaprState[String](key.value, encodedValue, e.value, null)
          case None    => new DaprState[String](key.value, encodedValue, null, null)
        new TransactionalStateOperation[String](OperationType.UPSERT, daprState)

      case StateOp.DeleteOp(key, etag) =>
        val daprState = etag match
          case Some(e) => new DaprState[String](key.value, null, e.value, null)
          case None    => new DaprState[String](key.value, null, null, null)
        new TransactionalStateOperation[String](OperationType.DELETE, daprState)

  private def isETagConflict(e: io.dapr.exceptions.DaprException): Boolean =
    val msg: String | Null = e.getMessage
    e.getHttpStatusCode == 409 || (msg != null && msg.contains("ABORTED"))
