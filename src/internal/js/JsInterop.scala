//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js

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

  /** Convert an SDK response value back into the JSON string (or `null`) that [[JsonCodec.decode]] expects.
    *
    * `HTTPClient.execute` returns the response body after `tryParseJson`: an empty body comes back as the empty string
    * `""` (because `JSON.parse("")` throws and the raw text is substituted), a JSON body as the parsed value. Absent
    * (`undefined`/`null`) and empty-body responses map to `null`, mirroring the JVM impls where an empty `Mono` yields
    * a `null` byte array.
    */
  def jsonStringOrNull(v: js.Any): String | Null =
    if isAbsent(v) then null else js.JSON.stringify(v)

  /** True when the SDK response denotes "no payload": `undefined`, `null`, or the empty string (the `tryParseJson`
    * artifact for an empty HTTP body).
    */
  def isAbsent(v: js.Any): Boolean =
    js.isUndefined(v) || (v == null) || ((v: Any) match
      case s: String => s.isEmpty
      case _         => false)

  /** dapr4s metadata map → the `KeyValueType` string dictionary the SDK options take. */
  def toDict(metadata: Map[MetadataKey, MetadataValue]): js.Dictionary[String] =
    val d = js.Dictionary.empty[String]
    metadata.foreach { case (k, v) => d(k.value) = v.value }
    d

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
