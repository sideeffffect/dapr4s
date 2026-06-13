//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js

import org.scalablytyped.runtime.StringDictionary

/** JSON/string/error bridging between dapr4s ([[JsonCodec]] works on JSON strings) and the Dapr JS SDK (which works on
  * parsed JS values). The JS analogue of `internal/Json.scala` + `internal/NullOps.scala` on the JVM.
  *
  * The bridge is `js.JSON.parse` on the way out and `js.JSON.stringify` on the way back. One consequence to be aware
  * of: a JSON document round-tripped through JS values is re-serialized in canonical JS form (insignificant whitespace
  * dropped, `1.0` becomes `1`, and integers beyond 2^53 lose precision) — semantically equal JSON for everything except
  * >53-bit integers, which JavaScript itself cannot represent.
  */
@scala.caps.assumeSafe
private[internal] object JsInterop:

  /** Parse a dapr4s-encoded JSON string into a JS value to hand to the SDK. */
  def parseJson(json: String): js.Any = js.JSON.parse(json)

  /** View a loosely-typed SDK value as `js.Any` so the `js.JSON` API accepts it.
    *
    * WHAT: `asInstanceOf[js.Any]` on a `scala.Any`-typed value.
    *
    * WHY: the ScalablyTyped facades model TypeScript `any` as `scala.Any` and TS unions as Scala 3 unions (e.g.
    * `state.get`'s `KeyValueType | String`); neither conforms to `js.Any`, which `js.JSON.stringify` requires — even
    * though the runtime value behind them is always a plain JavaScript value.
    *
    * WHY SAFE: every value passed here is read off a `dapr4styped.*` API, i.e. produced by JavaScript code, so it IS a
    * JavaScript value at runtime. The cast is a compile-time view change only (`asInstanceOf` to a JS type is unchecked
    * and erased) and cannot fail or change the value.
    */
  def asJsAny(v: Any): js.Any = v.asInstanceOf[js.Any]

  /** True when a parsed JSON document is a JS-'''falsy''' value: `null`, `false`, `0` (incl. `-0`), or `""`. (Empty
    * objects/arrays are truthy in JS and correctly excluded; `undefined` is checked defensively even though
    * `JSON.parse` can never produce it.)
    *
    * Why this matters: the SDK's `HTTPClient.execute` guards the request body with a plain truthiness check —
    * `if (params?.body)` in `node_modules/@dapr/dapr/implementation/Client/HTTPClient/HTTPClient.js` — so handing it a
    * falsy payload silently produces an '''empty''' request body on the wire. Callers that pass parsed payloads to the
    * SDK ([[PublishCapabilityImpl]], [[InvokeCapabilityImpl]]) must detect this case and bypass the SDK with a raw
    * fetch instead.
    */
  def isFalsyJson(v: js.Any): Boolean =
    js.isUndefined(v) || ((v: Any) match
      case null       => true
      case b: Boolean => !b
      case d: Double  => d == 0.0 // every JS number pattern-matches as Double on Scala.js; covers 0 and -0
      case s: String  => s.isEmpty
      case _          => false)

  /** Convert an SDK response value back into the JSON string (or `null`) that [[JsonCodec.decode]] expects.
    *
    * `HTTPClient.execute` returns the response body after `tryParseJson`: an empty body comes back as the empty string
    * `""` (because `JSON.parse("")` throws and the raw text is substituted), a JSON body as the parsed value. Absent
    * (`undefined`/`null`) and empty-body responses map to `null`, mirroring the JVM impls where an empty `Mono` yields
    * a `null` byte array.
    *
    * One documented, accepted divergence from the JVM (which sees the raw response bytes): a response document that
    * '''is''' the empty string (the two-byte JSON document `""`) parses to the empty JS string — the very same value
    * `tryParseJson` substitutes for an empty body — so post-SDK the two cases are indistinguishable and both map to
    * `null`/`None` here.
    */
  def jsonStringOrNull(v: js.Any): String | Null =
    if isAbsent(v) then null else js.JSON.stringify(v)

  /** True when the SDK response denotes "no payload": `undefined`, `null`, or the empty string (the `tryParseJson`
    * artifact for an empty HTTP body — which, see [[jsonStringOrNull]], also swallows a response document that is the
    * JSON empty string `""`). Takes `scala.Any` so the ScalablyTyped response types (TS `any` → `scala.Any`, TS unions
    * → Scala unions) can be tested without a cast — every check below is JS-value-agnostic.
    */
  def isAbsent(v: Any): Boolean =
    js.isUndefined(v) || (v == null) || (v match
      case s: String => s.isEmpty
      case _         => false)

  /** dapr4s metadata map → the string dictionary the ScalablyTyped SDK options take.
    *
    * The element type is generic because the generated facades want two shapes for what is one runtime concept:
    * `KeyValueType = StringDictionary[Any]` (pub/sub options, configuration metadata, invoker headers) and
    * `IRequestMetadata = StringDictionary[String]` (state-save metadata). `StringDictionary` is invariant, so the
    * expected type at the call site selects `V`; the runtime object is the same plain string-valued JS object either
    * way.
    */
  def toDict[V >: String](metadata: Map[MetadataKey, MetadataValue]): StringDictionary[V] =
    StringDictionary(metadata.toSeq.map { case (k, v) => k.value -> (v.value: V) }*)

  /** A parsed SDK HTTP API failure.
    *
    * @param status
    *   the HTTP status code returned by the sidecar
    * @param errorMsg
    *   the raw response body (typically the sidecar's `{"errorCode": ..., "message": ...}` JSON)
    */
  final case class SdkHttpFailure(status: Int, errorMsg: String)

  /** Decode the SDK's HTTP error convention from a rejected/soft-failure `js.Error`.
    *
    * `HTTPClient.execute` rejects non-2xx/3xx responses with a plain `Error` whose '''message''' is
    * `JSON.stringify({error: statusText, error_msg: bodyText, status: number})` — there is no typed error hierarchy.
    * Returns `None` when the message does not follow that convention (network errors, gRPC `ConnectError`s, ...).
    */
  def sdkFailureOf(error: js.Error): Option[SdkHttpFailure] =
    try
      val parsed = js.JSON.parse(error.message).asInstanceOf[js.Dynamic]
      // WHAT: asInstanceOf on a js.JSON.parse result.
      // WHY: JSON.parse is typed js.Dynamic-producing js.Any; we need property access on it.
      // WHY SAFE: js.Dynamic is the untyped view of any JS value — the cast is a no-op at runtime, and the
      // property reads below are guarded (typeof checks) before being trusted.
      val status = parsed.selectDynamic("status")
      val errorMsg = parsed.selectDynamic("error_msg")
      (status: Any) match
        case s: Double =>
          val msg = (errorMsg: Any) match
            case m: String => m
            case _         => ""
          Some(SdkHttpFailure(s.toInt, msg))
        case _ => None
    catch
      // JSON.parse throws SyntaxError for non-JSON messages — that simply means "not the SDK HTTP convention".
      case _: js.JavaScriptException => None

  /** [[sdkFailureOf]] for exceptions caught in Scala: unwrap `js.JavaScriptException` carrying a JS `Error`. */
  def sdkFailureOf(t: Throwable): Option[SdkHttpFailure] =
    t match
      case js.JavaScriptException(e: js.Error) => sdkFailureOf(e)
      case _                                   => None
