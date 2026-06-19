//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import dapr4s.state.*
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import JsInterop.*
import dapr4styped.daprDapr.anon.{PartialStateDeleteOptions, PartialStateGetOptions}
// The enum TYPES come from the deep modules (types are erased — no import is emitted), but the
// VALUES are read off the "@dapr/dapr" root re-exports: ScalablyTyped's deep-module specifiers
// carry no `.js` extension and `@dapr/dapr` has no `exports` map, so Node ESM (the Wasm/JSPI
// production target) cannot resolve them — see the same note in InvokeCapabilityImpl.
import dapr4styped.daprDapr.enumStateConcurrencyDotenumMod.StateConcurrencyEnum
import dapr4styped.daprDapr.enumStateConsistencyDotenumMod.StateConsistencyEnum
import dapr4styped.daprDapr.mod.{StateConcurrencyEnum as SdkConcurrency, StateConsistencyEnum as SdkConsistency}
import dapr4styped.daprDapr.typesKeyValuePairDottypeMod.KeyValuePairType
import dapr4styped.daprDapr.typesOperationDottypeMod.OperationType
import dapr4styped.daprDapr.typesRequestDottypeMod.IRequest
import dapr4styped.daprDapr.typesStateStateOptionsDottypeMod.IStateOptions
import dapr4styped.daprDapr.typesStateStateQueryDottypeMod.StateQueryType
import dapr4styped.daprDapr.typesStateStateSaveOptionsDottypeMod.StateSaveOptions
import dapr4styped.daprDapr.typesStateStateSaveResponseTypeMod.StateSaveResponseType
import dapr4styped.node.globalsMod.global as NodeGlobals
import dapr4styped.undiciTypes.fetchMod.RequestInit

@scala.caps.assumeSafe
private object StateCapabilityImpl:

  /** dapr4s enum → the SDK's numeric `StateConsistencyEnum` (CONSISTENCY_EVENTUAL = 1, CONSISTENCY_STRONG = 2);
    * `Default` maps to CONSISTENCY_UNSPECIFIED (0), which `getStateConsistencyValue` (`utils/Client.util.js`) turns
    * into "no query parameter" exactly like `undefined` — the same store-default behaviour the JVM impl gets from
    * passing a `null` Java enum. (The ScalablyTyped `IStateOptions` requires both fields, so the explicit UNSPECIFIED
    * member replaces the hand facade's `js.undefined`; the wire behaviour is identical.)
    */
  private def toJsConsistency(c: StateConsistency): StateConsistencyEnum =
    c match
      case StateConsistency.Default  => SdkConsistency.CONSISTENCY_UNSPECIFIED
      case StateConsistency.Eventual => SdkConsistency.CONSISTENCY_EVENTUAL
      case StateConsistency.Strong   => SdkConsistency.CONSISTENCY_STRONG

  /** dapr4s enum → numeric `StateConcurrencyEnum` (CONCURRENCY_FIRST_WRITE = 1, CONCURRENCY_LAST_WRITE = 2); `Default`
    * → CONCURRENCY_UNSPECIFIED (0), same "no query parameter" mapping as [[toJsConsistency]].
    */
  private def toJsConcurrency(c: StateConcurrency): StateConcurrencyEnum =
    c match
      case StateConcurrency.Default    => SdkConcurrency.CONCURRENCY_UNSPECIFIED
      case StateConcurrency.FirstWrite => SdkConcurrency.CONCURRENCY_FIRST_WRITE
      case StateConcurrency.LastWrite  => SdkConcurrency.CONCURRENCY_LAST_WRITE

  /** The `?consistency=` query value of the raw state HTTP API, used by [[StateCapabilityImpl.getWithETag]]. */
  private def consistencyQuery(c: StateConsistency): Option[String] =
    c match
      case StateConsistency.Default  => None
      case StateConsistency.Eventual => Some("eventual")
      case StateConsistency.Strong   => Some("strong")

  private def decode[T: JsonCodec](raw: String | Null): T =
    JsonCodec.decodeOrThrow[T](raw)

  /** WHAT: asInstanceOf putting a plain etag '''string''' where ScalablyTyped wants the SDK's `IEtag` object type
    * (`{value: string}` per `types/Etag.type.ts`).
    *
    * WHY: the SDK's TS type is wrong against the wire contract. `state.transaction` sends the operations array verbatim
    * (`body: {operations, metadata}` — `implementation/Client/HTTPClient/state.js`; no serializer touches the etag),
    * and daprd's `POST /v1.0/state/{store}/transaction` unmarshals `request.etag` as a JSON '''string''' — an
    * `{value: ...}` object would fail the request. The hand-written facade shipped the string form and was verified
    * against a live sidecar; this preserves that wire format.
    *
    * WHY SAFE: erased, zero-cost; the value lands in the JSON body exactly as daprd's API reference
    * (`TransactionalStateOperation.request.etag: string`) requires.
    */
  private def toJsEtag(etag: ETag): dapr4styped.daprDapr.typesEtagDottypeMod.IEtag =
    etag.value.asInstanceOf[dapr4styped.daprDapr.typesEtagDottypeMod.IEtag]

  private def toJsOp(op: StateOp): OperationType =
    op match
      case StateOp.UpsertOp(key, encodedValue, etag) =>
        val request = IRequest(key.value).setValue(parseJson(encodedValue.value))
        etag.foreach(e => request.setEtag(toJsEtag(e)): Unit)
        OperationType(operation = "upsert", request = request)
      case StateOp.DeleteOp(key, etag) =>
        val request = IRequest(key.value)
        etag.foreach(e => request.setEtag(toJsEtag(e)): Unit)
        OperationType(operation = "delete", request = request)

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
    * (`implementation/Client/HTTPClient/state.js`, typed `StateSaveResponseType` with an optional `error`); the JVM
    * impl's `try/catch DaprException` becomes an inspection of that field here. Non-conflict errors are rethrown as
    * `js.JavaScriptException` to mirror the JVM, where a non-conflict `DaprException` propagates.
    */
  private def conflictOrThrow(
      response: StateSaveResponseType,
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
          PartialStateGetOptions().setConsistency(toJsConsistency(consistency)),
        )
    // The result is typed `KeyValueType | String` by ScalablyTyped (the TS interface), but at runtime it is the
    // parsed JSON body whatever its shape — asJsAny views the union as the js.Any it is.
    val raw = JsAwait.await(promise)
    // A missing key answers 204 with an empty body, which HTTPClient.execute's tryParseJson surfaces as the empty
    // string — isAbsent maps that (and undefined/null) to None, mirroring the JVM's null/empty-value filter.
    if isAbsent(raw) then None else Some(decode[T](js.JSON.stringify(asJsAny(raw))))

  def getWithETag[T: JsonCodec](
      key: StateStoreKey,
      consistency: StateConsistency = StateConsistency.Default,
  ): StateEntry[T] =
    // The SDK cannot express this operation: the sidecar returns the ETag of a single-key read in the `ETag`
    // RESPONSE HEADER, but `HTTPClient.execute` returns only the parsed body and discards all response headers
    // (implementation/Client/HTTPClient/HTTPClient.js), and `state.getBulk` (whose body does carry etags) cannot
    // pass the consistency hint. So this method calls the raw state HTTP API with fetch and reads the header
    // itself — the same SDK-bypass precedent as the JVM `HttpActorContext`.
    import ActorCapabilityImpl.urlSegment
    val query = consistencyQuery(consistency).fold("")(c => s"?consistency=$c")
    val base = ActorCapabilityImpl.httpBase(scope.sidecar)
    val url = s"$base/v1.0/state/${urlSegment(storeName.value)}/${urlSegment(key.value)}$query"
    val response = JsAwait.await(
      NodeGlobals.fetch(url, RequestInit().setMethod("GET").setHeaders(ActorCapabilityImpl.baseHeaders(scope.sidecar))),
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
      // ScalablyTyped types the response items as `KeyValueType` (`{[key: string]: any}` in the TS interface), but
      // the runtime shape — verified in implementation/Client/HTTPClient/state.js — is the raw sidecar response to
      // `POST /v1.0/state/{store}/bulk`, passed through verbatim: `[{key, data, etag}]`, with `data` absent for
      // missing keys. The fields are read through the dictionary view with type-tested values, so a shape change
      // upstream degrades to absent entries instead of a ClassCastException.
      val items = JsAwait.await(scope.client.state.getBulk(storeName.value, keys.map(_.value).toJSArray))
      items.map { item =>
        val key = item.get("key") match
          case Some(s: String) => s
          case _               => ""
        val raw = item.get("data").filterNot(isAbsent)
        val etag = item.get("etag") match
          case Some(s: String) => Some(ETag(s))
          case _               => None
        StateStoreKey(key) -> StateEntry(raw.map(d => decode[T](js.JSON.stringify(asJsAny(d)))), etag)
      }.toMap

  def save[T: JsonCodec](key: StateStoreKey, value: T): Unit =
    val json = summon[JsonCodec[T]].encode(value)
    val entry = KeyValuePairType(key = key.value, value = parseJson(json))
    val response = JsAwait.await(scope.client.state.save(storeName.value, js.Array(entry)))
    // `state.save` soft-fails ({error} instead of rejecting); rethrow to mirror the JVM, where saveState throws.
    response.error.toOption.foreach(e => throw js.JavaScriptException(e))

  def saveBulk[T: JsonCodec](entries: Seq[(StateStoreKey, T)]): Unit =
    if entries.nonEmpty then
      val jsEntries = entries.map { case (key, value) =>
        KeyValuePairType(key = key.value, value = parseJson(summon[JsonCodec[T]].encode(value)))
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
    val entry = KeyValuePairType(key = key.value, value = parseJson(json))
      .setEtag(etag.value)
      .setOptions(
        IStateOptions(
          concurrency = toJsConcurrency(concurrency),
          consistency = toJsConsistency(consistency),
        ),
      )
    val options = StateSaveOptions().setMetadata(toDict(metadata))
    val response = JsAwait.await(scope.client.state.save(storeName.value, js.Array(entry), options))
    conflictOrThrow(response, key, etag)

  def delete(key: StateStoreKey): Unit =
    val response = JsAwait.await(scope.client.state.delete(storeName.value, key.value))
    response.error.toOption.foreach(e => throw js.JavaScriptException(e))

  def deleteWithETag(
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency = StateConsistency.Default,
      concurrency: StateConcurrency = StateConcurrency.FirstWrite,
  ): Option[ETagMismatchException] =
    val options = PartialStateDeleteOptions()
      .setEtag(etag.value)
      .setConsistency(toJsConsistency(consistency))
      .setConcurrency(toJsConcurrency(concurrency))
    val response = JsAwait.await(scope.client.state.delete(storeName.value, key.value, options))
    conflictOrThrow(response, key, etag)

  def transaction(ops: Seq[StateOp]): Unit =
    JsAwait.await(scope.client.state.transaction(storeName.value, ops.map(toJsOp).toJSArray)): Unit

  def queryState[T: JsonCodec](query: StateQuery): List[StateEntry[T]] =
    // WHAT: asInstanceOf[StateQueryType] on the parsed query document.
    // WHY: dapr4s's StateQuery is the user's raw JSON query string (parse-don't-validate happens at the sidecar);
    // ScalablyTyped wants the structured StateQueryType trait, but there is no checked conversion from a parsed
    // JSON value to a structural trait — the cast IS the conversion (erased, zero-cost).
    // WHY SAFE: the SDK never introspects the object beyond JSON.stringify-ing it onto the wire
    // (implementation/Client/HTTPClient/state.js `query`), so any well-formed query document behaves identically
    // to one built field-by-field; a malformed document is rejected by the sidecar exactly as on the JVM.
    val jsQuery = parseJson(query.value).asInstanceOf[StateQueryType]
    val response = JsAwait.await(scope.client.state.query(storeName.value, jsQuery))
    // `results` is required in the ST type but only conditionally present at runtime: the SDK substitutes
    // `{results: []}` solely for an EMPTY response body (implementation/Client/HTTPClient/state.js `query`);
    // a JSON body without a `results` field (e.g. `{"token": ...}` when the repeated field is empty) passes
    // through verbatim. Guard like getBulk does, mirroring the JVM twin's getResults.toOption.fold(Nil).
    val rawResults = response.results
    val results = if js.isUndefined(rawResults) || (rawResults: Any) == null then List.empty else rawResults.toList
    results.map { item =>
      val raw = Option(item.data).filterNot(isAbsent)
      val etag = item.etag.toOption.map(ETag(_))
      StateEntry(raw.map(d => decode[T](js.JSON.stringify(asJsAny(d)))), etag)
    }
