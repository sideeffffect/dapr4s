package dapr.safe.internal

// NOTE: @assumeSafe would be applied here once Scala 3 stable supports it.
// Currently this annotation is only available in nightly Scala 3 builds.

import dapr.safe.*
import io.dapr.client.domain.{State as DaprState, StateOptions, TransactionalStateOperation}
import io.dapr.client.domain.TransactionalStateOperation.OperationType

import scala.jdk.CollectionConverters.*

private[safe] final class StateCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: StoreName
) extends StateCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def get[T: JsonCodec](key: String): Option[T] =
    checkOpen()
    try
      val state: DaprState[String] =
        scope.daprClient.getState(storeName.value, key, classOf[String]).block()
      val raw = state.getValue
      if raw == null || raw.isEmpty then None
      else
        Some(JsonCodec.decodeOrThrow[T](raw))
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def getWithETag[T: JsonCodec](key: String): StateEntry[T] =
    checkOpen()
    try
      val state: DaprState[String] =
        scope.daprClient.getState(storeName.value, key, classOf[String]).block()
      val raw   = state.getValue
      val etag  = state.getEtag
      if raw == null || raw.isEmpty then
        StateEntry(None, Option(etag).map(ETag(_)))
      else
        val value = Some(JsonCodec.decodeOrThrow[T](raw))
        StateEntry(value, Option(etag).map(ETag(_)))
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def save[T: JsonCodec](key: String, value: T): Unit =
    checkOpen()
    try
      val json = summon[JsonCodec[T]].encode(value)
      scope.daprClient.saveState(storeName.value, key, json).block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def saveWithETag[T: JsonCodec](key: String, value: T, etag: ETag): Unit =
    checkOpen()
    try
      val json = summon[JsonCodec[T]].encode(value)
      val opts = new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE)
      scope.daprClient
        .saveState(storeName.value, key, etag.value, json, opts)
        .block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        if isETagConflict(e) then throw ETagMismatchException(key, etag)
        else throw DaprException(e.getMessage, e)

  def delete(key: String): Unit =
    checkOpen()
    try
      scope.daprClient.deleteState(storeName.value, key).block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

  def deleteWithETag(key: String, etag: ETag): Unit =
    checkOpen()
    try
      val opts = new StateOptions(null, StateOptions.Concurrency.FIRST_WRITE)
      scope.daprClient.deleteState(storeName.value, key, etag.value, opts).block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        if isETagConflict(e) then throw ETagMismatchException(key, etag)
        else throw DaprException(e.getMessage, e)

  def transaction(ops: Seq[StateOp]): Unit =
    checkOpen()
    try
      val javaOps: java.util.List[TransactionalStateOperation[?]] =
        ops.map(toJavaOp).asJava
      scope.daprClient
        .executeStateTransaction(storeName.value, javaOps)
        .block()
    catch
      case e: DaprException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprException(e.getMessage, e)

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
    e.getHttpStatusCode == 409 || e.getMessage.contains("ABORTED")
