//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import JsInterop.*

@scala.caps.assumeSafe
private object StateCapabilityImpl:

  /** dapr4s enum → numeric `StateConsistencyEnum` (CONSISTENCY_EVENTUAL = 1, CONSISTENCY_STRONG = 2); `Default` maps to
    * `undefined`, which `getStateConsistencyValue` turns into "no query parameter" — the same store-default behaviour
    * the JVM impl gets from passing a `null` Java enum.
    */
  private def toJsConsistency(c: StateConsistency): js.UndefOr[Int] =
    c match
      case StateConsistency.Default  => js.undefined
      case StateConsistency.Eventual => 1
      case StateConsistency.Strong   => 2

  /** dapr4s enum → numeric `StateConcurrencyEnum` (CONCURRENCY_FIRST_WRITE = 1, CONCURRENCY_LAST_WRITE = 2). */
  private def toJsConcurrency(c: StateConcurrency): js.UndefOr[Int] =
    c match
      case StateConcurrency.Default    => js.undefined
      case StateConcurrency.FirstWrite => 1
      case StateConcurrency.LastWrite  => 2

  /** The `?consistency=` query value of the raw state HTTP API, used by [[StateCapabilityImpl.getWithETag]]. */
  private def consistencyQuery(c: StateConsistency): Option[String] =
    c match
      case StateConsistency.Default  => None
      case StateConsistency.Eventual => Some("eventual")
      case StateConsistency.Strong   => Some("strong")

  private def decode[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)

  private def toJsOp(op: StateOp): facade.StateTransactionOperation =
    op match
      case StateOp.UpsertOp(key, encodedValue, etag) =>
        new facade.StateTransactionOperation(
          operation = "upsert",
          request = new facade.StateTransactionRequest(
            key = key.value,
            value = parseJson(encodedValue.value),
            etag = etag.fold[js.UndefOr[String]](js.undefined)(_.value),
          ),
        )
      case StateOp.DeleteOp(key, etag) =>
        new facade.StateTransactionOperation(
          operation = "delete",
          request = new facade.StateTransactionRequest(
            key = key.value,
            etag = etag.fold[js.UndefOr[String]](js.undefined)(_.value),
          ),
        )

  /** ETag-conflict detection for conditional writes, the HTTP twin of the JVM impl's `isETagConflict(DaprException)`
    * (`getHttpStatusCode == 409 || message.contains("ABORTED")`): the sidecar's HTTP API answers a mismatching
    * `If-Match`/etag with '''409 Conflict''', and newer daprd versions additionally embed the
    * `DAPR_STATE_ETAG_MISMATCH` error code in the body — both are accepted, mirroring the JVM's dual check (status code
    * OR message marker) adapted from the gRPC to the HTTP transport.
    */
  private def isETagConflict(f: SdkHttpFailure): Boolean =
    f.status == 409 || f.errorMsg.contains("ETAG_MISMATCH")

  /** Map a `state.save`/`state.delete` soft failure: `None` on success, `Some` on ETag conflict, rethrow otherwise.
    *
    * The SDK does not reject these calls — it catches the error and returns `{error}`
    * (`implementation/Client/HTTPClient/state.js`); the JVM impl's `try/catch DaprException` becomes an inspection of
    * that field here. Non-conflict errors are rethrown as `js.JavaScriptException` to mirror the JVM, where a
    * non-conflict `DaprException` propagates.
    */
  private def conflictOrThrow(
      response: facade.SoftFailureResponse,
      key: StateStoreKey,
      etag: ETag,
  ): Option[ETagMismatchException] =
    response.error.toOption match
      case None        => None
      case Some(error) =>
        if sdkFailureOf(error).exists(isETagConflict) then Some(ETagMismatchException(key, etag))
        else throw js.JavaScriptException(error)

@scala.caps.assumeSafe
private[internal] final class StateCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: StateStoreName,
) extends StateCapability:

  import StateCapabilityImpl.*

  def get[T: JsonCodec](key: StateStoreKey, consistency: StateConsistency = StateConsistency.Default): Option[T] =
    val promise =
      if consistency == StateConsistency.Default then scope.client.state.get(storeName.value, key.value)
      else
        scope.client.state.get(
          storeName.value,
          key.value,
          new facade.StateGetOptions(consistency = toJsConsistency(consistency)),
        )
    val raw = JsAwait.await(promise)
    // A missing key answers 204 with an empty body, which HTTPClient.execute's tryParseJson surfaces as the empty
    // string — isAbsent maps that (and undefined/null) to None, mirroring the JVM's null/empty-value filter.
    if isAbsent(raw) then None else Some(decode[T](js.JSON.stringify(raw)))

  def getWithETag[T: JsonCodec](
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  ): StateEntry[T] =
    // The SDK cannot express this operation: the sidecar returns the ETag of a single-key read in the `ETag`
    // RESPONSE HEADER, but `HTTPClient.execute` returns only the parsed body and discards all response headers
    // (implementation/Client/HTTPClient/HTTPClient.js), and `state.getBulk` (whose body does carry etags) cannot
    // pass the consistency hint. So this method calls the raw state HTTP API with fetch and reads the header
    // itself — the same SDK-bypass precedent as the JVM `HttpActorContext`.
    val query = consistencyQuery(consistency).fold("")(c => s"?consistency=$c")
    val url = s"${ActorCapabilityImpl.httpBase(scope.sidecar)}/v1.0/state/${storeName.value}/${key.value}$query"
    val response = JsAwait.await(
      facade.NodeGlobals.fetch(
        url,
        new facade.FetchRequestInit(method = "GET", headers = ActorCapabilityImpl.baseHeaders(scope.sidecar)),
      ),
    )
    val body = JsAwait.await(response.text())
    if response.status == 204 then StateEntry[T](None, None)
    else if response.status >= 400 then throw new RuntimeException(s"Dapr API error ${response.status} at $url: $body")
    else
      val etag = response.headers.get("etag") match
        case null => None
        case e    => Some(ETag(e))
      val value = if body.isEmpty then None else Some(decode[T](body))
      StateEntry(value, etag)

  def getBulk[T: JsonCodec](keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]] =
    if keys.isEmpty then Map.empty
    else
      val items = JsAwait.await(scope.client.state.getBulk(storeName.value, keys.map(_.value).toJSArray))
      items.map { item =>
        val raw = item.data.toOption.filterNot(isAbsent)
        val etag = item.etag.toOption.map(ETag(_))
        StateStoreKey(item.key) -> StateEntry(raw.map(d => decode[T](js.JSON.stringify(d))), etag)
      }.toMap

  def save[T: JsonCodec](key: StateStoreKey, value: T): Unit =
    val json = summon[JsonCodec[T]].encode(value)
    val entry = new facade.StateKeyValuePair(key = key.value, value = parseJson(json))
    val response = JsAwait.await(scope.client.state.save(storeName.value, js.Array(entry)))
    // `state.save` soft-fails ({error} instead of rejecting); rethrow to mirror the JVM, where saveState throws.
    response.error.toOption.foreach(e => throw js.JavaScriptException(e))

  def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)]): Unit =
    if entries.nonEmpty then
      val jsEntries = entries.map { case (key, value) =>
        new facade.StateKeyValuePair(key = key.value, value = parseJson(summon[JsonCodec[T]].encode(value)))
      }.toJSArray
      val response = JsAwait.await(scope.client.state.save(storeName.value, jsEntries))
      response.error.toOption.foreach(e => throw js.JavaScriptException(e))

  def saveWithETag[T: JsonCodec](
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue] = Map.empty,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    val json = summon[JsonCodec[T]].encode(value)
    val entry = new facade.StateKeyValuePair(
      key = key.value,
      value = parseJson(json),
      etag = etag.value,
      options = new facade.StateOperationOptions(
        consistency = toJsConsistency(consistency),
        concurrency = toJsConcurrency(concurrency),
      ),
    )
    val options = new facade.StateSaveOptions(metadata = toDict(metadata))
    val response = JsAwait.await(scope.client.state.save(storeName.value, js.Array(entry), options))
    conflictOrThrow(response, key, etag)

  def delete(key: StateStoreKey): Unit =
    val response =
      JsAwait.await(scope.client.state.delete(storeName.value, key.value, new facade.StateDeleteOptions()))
    response.error.toOption.foreach(e => throw js.JavaScriptException(e))

  def deleteWithETag(
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    val options = new facade.StateDeleteOptions(
      etag = etag.value,
      consistency = toJsConsistency(consistency),
      concurrency = toJsConcurrency(concurrency),
    )
    val response = JsAwait.await(scope.client.state.delete(storeName.value, key.value, options))
    conflictOrThrow(response, key, etag)

  def transaction(ops: Seq[StateOp]): Unit =
    JsAwait.await(scope.client.state.transaction(storeName.value, ops.map(toJsOp).toJSArray)): Unit

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] =
    val response = JsAwait.await(scope.client.state.query(storeName.value, parseJson(query.value)))
    response.results.toOption.fold(List.empty[StateEntry[T]]) { items =>
      items.toList.map { item =>
        val raw = item.data.toOption.filterNot(isAbsent)
        val etag = item.etag.toOption.map(ETag(_))
        StateEntry(raw.map(d => decode[T](js.JSON.stringify(d))), etag)
      }
    }
