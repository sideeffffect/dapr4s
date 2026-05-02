package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{State as DaprState, StateOptions, TransactionalStateOperation}
import io.dapr.client.domain.TransactionalStateOperation.OperationType
import language.experimental.saferExceptions

import scala.jdk.CollectionConverters.*

@scala.caps.assumeSafe
private[safe] final class StateCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: StoreName
) extends StateCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get[T: JsonCodec](key: String): Option[T] throws DaprStateException =
    checkOpen()
    try
      val state: DaprState[String] | Null =
        scope.client.getState(storeName.value, key, classOf[String]).block()
      if state == null then return None
      val raw: String | Null = state.getValue
      if raw == null || raw.isEmpty then None
      else
        JsonCodec.decodeOrThrow[T](raw) match
          case v => Some(v)
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def getWithETag[T: JsonCodec](key: String): StateEntry[T] throws DaprStateException =
    checkOpen()
    try
      val state: DaprState[String] | Null =
        scope.client.getState(storeName.value, key, classOf[String]).block()
      if state == null then return StateEntry(None, None)
      val raw: String | Null  = state.getValue
      val etag: String | Null = state.getEtag
      if raw == null || raw.isEmpty then
        StateEntry(None, Option(etag.asInstanceOf[String]).map(ETag(_)))
      else
        val decoded = JsonCodec.decodeOrThrow[T](raw) match
          case v => Some(v)
        StateEntry(decoded, Option(etag.asInstanceOf[String]).map(ETag(_)))
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def getBulk[T: JsonCodec](keys: Seq[String]): Map[String, StateEntry[T]] throws DaprStateException =
    checkOpen()
    try
      keys.map { key =>
        key -> getWithETag[T](key)
      }.toMap
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def save[T: JsonCodec](key: String, value: T): Unit throws DaprStateException =
    checkOpen()
    try
      val json = summon[JsonCodec[T]].encode(value)
      scope.client.saveState(storeName.value, key, json).block(): Unit
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def saveBulk[T: JsonCodec](entries: Seq[(String, T)]): Unit throws DaprStateException =
    checkOpen()
    try
      entries.foreach { case (key, value) =>
        save[T](key, value)
      }
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def saveWithETag[T: JsonCodec](key: String, value: T, etag: ETag): Unit throws DaprStateException =
    checkOpen()
    try
      val json = summon[JsonCodec[T]].encode(value)
      val opts = new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE)
      scope.client
        .saveState(storeName.value, key, etag.value, json, opts)
        .block(): Unit
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        if isETagConflict(e) then throw ETagMismatchException(key, etag)
        else throw DaprStateException(e.getMessage.nn, e)

  def delete(key: String): Unit throws DaprStateException =
    checkOpen()
    try
      scope.client.deleteState(storeName.value, key).block(): Unit
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def deleteWithETag(key: String, etag: ETag): Unit throws DaprStateException =
    checkOpen()
    try
      val opts = new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE)
      scope.client.deleteState(storeName.value, key, etag.value, opts).block(): Unit
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        if isETagConflict(e) then throw ETagMismatchException(key, etag)
        else throw DaprStateException(e.getMessage.nn, e)

  def transaction(ops: Seq[StateOp]): Unit throws DaprStateException =
    checkOpen()
    try
      val javaOps: java.util.List[TransactionalStateOperation[?]] =
        ops.map(toJavaOp).asJava
      scope.client
        .executeStateTransaction(storeName.value, javaOps)
        .block(): Unit
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] throws DaprStateException =
    checkOpen()
    try
      val previewClient = scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]
      val result: io.dapr.client.domain.QueryStateResponse[String] | Null =
        previewClient.queryState(storeName.value, query.value, classOf[String]).block()
      if result == null then return List.empty
      val items = result.getResults
      if items == null then return List.empty
      items.asScala.toList.map { item =>
        val raw: String | Null  = item.getValue
        val etag: String | Null = item.getEtag
        if raw == null || raw.isEmpty then
          StateEntry[T](None, Option(etag.asInstanceOf[String]).map(ETag(_)))
        else
          val decoded = JsonCodec.decodeOrThrow[T](raw) match
            case v => Some(v)
          StateEntry(decoded, Option(etag.asInstanceOf[String]).map(ETag(_)))
      }
    catch
      case e: DaprStateException => throw e
      case e: DaprException => throw DaprStateException(e.getMessage, e)
      case e: io.dapr.exceptions.DaprException =>
        throw DaprStateException(e.getMessage.nn, e)
      case e: ClassCastException =>
        throw DaprStateException("queryState requires DaprPreviewClient (not available)", e)

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def toJavaOp(op: StateOp): TransactionalStateOperation[?] =
    op match
      case StateOp.UpsertOp(key, encodedValue, etag) =>
        val daprState = etag match
          case Some(e) => new DaprState[String](key, encodedValue, e.value, null)
          case None    => new DaprState[String](key, encodedValue, null, null)
        new TransactionalStateOperation[String](OperationType.UPSERT, daprState)

      case StateOp.DeleteOp(key, etag) =>
        val daprState = etag match
          case Some(e) => new DaprState[String](key, null, e.value, null)
          case None    => new DaprState[String](key, null, null, null)
        new TransactionalStateOperation[String](OperationType.DELETE, daprState)

  private def isETagConflict(e: io.dapr.exceptions.DaprException): Boolean =
    val msg: String | Null = e.getMessage
    e.getHttpStatusCode == 409 || (msg != null && msg.contains("ABORTED"))
