//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js
import scala.util.control.NonFatal

/** The Scala.js wall's single translation point from JS SDK / gRPC / `fetch` failures to [[dapr4s.DaprException]] — the
  * twin of the JVM's `JvmErrors`.
  *
  * Sidecar errors arrive in four shapes, all normalised here into a [[SidecarError]] for the shared
  * [[DaprErrors.translate]]:
  *   - the SDK HTTP client's rejection: a plain `Error` whose message is
  *     `JSON.stringify({error, error_msg: <daprd body>, status})` ([[JsInterop.sdkFailureOf]]); the daprd body is the
  *     `{"errorCode", "message", "details"}` JSON;
  *   - the same `Error` returned as a soft failure (`{error}`) by `state.save`/`state.delete`/`pubsub.publish`;
  *   - a gRPC error from the SDK's gRPC client (configuration, crypto — a `ConnectError`) or the durabletask workflow
  *     client (a `@grpc/grpc-js` `ServiceError`): a numeric `code` (the gRPC status number) plus the description in
  *     `rawMessage` (Connect) or `details` (grpc-js);
  *   - a raw `fetch` response (the bypass paths: invoke, actor state, falsy publish, consistency get) — [[fromHttp]].
  *
  * A `fetch` that cannot reach the sidecar at all rejects with `TypeError("fetch failed")`; it becomes
  * `DaprApiException("UNAVAILABLE")`, the same code a gRPC `UNAVAILABLE` gets on the JVM.
  */
@scala.caps.assumeSafe
private[internal] object JsErrors:

  /** gRPC status numbers → names, as `io.grpc.Status.Code.name` spells them on the JVM. */
  private val grpcNames: Vector[String] = Vector(
    "OK",
    "CANCELLED",
    "UNKNOWN",
    "INVALID_ARGUMENT",
    "DEADLINE_EXCEEDED",
    "NOT_FOUND",
    "ALREADY_EXISTS",
    "PERMISSION_DENIED",
    "RESOURCE_EXHAUSTED",
    "FAILED_PRECONDITION",
    "ABORTED",
    "OUT_OF_RANGE",
    "UNIMPLEMENTED",
    "INTERNAL",
    "UNAVAILABLE",
    "DATA_LOSS",
    "UNAUTHENTICATED",
  )

  /** Map `t` (as rethrown by `js.await`) to a [[dapr4s.DaprException]] if it is a JS error from a Dapr call; return it
    * unchanged otherwise (a Scala exception thrown by dapr4s code itself, e.g. a codec error).
    */
  def translate(ctx: ErrorContext, t: Throwable): Throwable =
    t match
      case d: DaprException                    => d
      case js.JavaScriptException(e: js.Error) => fromJsError(ctx, e, t)
      // A rejection with a non-Error value (a string, a plain object): nothing to parse; keep it as the message.
      case js.JavaScriptException(other) => DaprApiException("UNKNOWN", String.valueOf(other), t)
      case other                         => other

  /** Translate a JS `Error` from a Dapr call. `cause` is what to keep as the exception cause (the
    * `js.JavaScriptException` wrapper when there is one).
    */
  def fromJsError(ctx: ErrorContext, e: js.Error, cause: Throwable | Null): DaprException =
    JsInterop.sdkFailureOf(e) match
      case Some(JsInterop.SdkHttpFailure(status, body)) =>
        DaprErrors.translate(ctx, parseErrorBody(status, body).getOrElse(plainHttp(status, body)), cause)
      case None =>
        grpcError(e) match
          case Some(err)                                                    => DaprErrors.translate(ctx, err, cause)
          case None if e.name == "TypeError" && e.message == "fetch failed" =>
            DaprErrors.unavailable(s"cannot reach the Dapr sidecar: ${causeMessage(e)}", cause)
          case None => DaprApiException("UNKNOWN", s"${e.name}: ${e.message}", cause)

  /** A non-2xx answer from a raw `fetch` to the sidecar, body included. */
  def fromHttp(ctx: ErrorContext, status: Int, body: String): DaprException =
    DaprErrors.translate(ctx, parseErrorBody(status, body).getOrElse(plainHttp(status, body)), null)

  private def plainHttp(status: Int, body: String): SidecarError =
    SidecarError(None, None, Some(status), None, body)

  /** Parse an HTTP body as daprd's `{"errorCode": ..., "message": ..., "details": [{"reason": ...}]}` error shape.
    * `None` when the body is not JSON or has no string `errorCode`.
    *
    * WHAT: `asInstanceOf[js.Dynamic]` on `JSON.parse` results. WHY: property access on parsed JSON. WHY SAFE:
    * `js.Dynamic` is the untyped view of any JS value — a no-op at runtime — and every read is type-tested before use.
    */
  def parseErrorBody(status: Int, body: String): Option[SidecarError] =
    try
      val parsed = js.JSON.parse(body).asInstanceOf[js.Dynamic]
      if js.typeOf(parsed) != "object" || parsed == null then None
      else
        stringField(parsed, "errorCode").map { code =>
          val reason = (parsed.selectDynamic("details"): Any) match
            case arr: js.Array[?] =>
              arr.iterator.flatMap(d => stringField(d.asInstanceOf[js.Dynamic], "reason")).nextOption()
            case _ => None
          SidecarError(
            errorCode = Some(code),
            reason = reason,
            httpStatus = Some(status),
            grpcStatus = None,
            message = stringField(parsed, "message").getOrElse(""),
          )
        }
    catch case NonFatal(_) => None // not JSON → not a Dapr error body

  private def stringField(o: js.Dynamic, field: String): Option[String] =
    if o == null || js.isUndefined(o) || js.typeOf(o) != "object" then None
    else
      (o.selectDynamic(field): Any) match
        case s: String => Some(s)
        case _         => None

  /** A gRPC error: a numeric `code` in the gRPC range plus a description (`rawMessage` on a Connect `ConnectError`,
    * `details` on a grpc-js `ServiceError`). The `google.rpc.ErrorInfo` reason is read from Connect's decoded `details`
    * when present; grpc-js does not decode it, which the message-based fallback in [[DaprErrors]] covers.
    *
    * WHAT: `asInstanceOf[js.Dynamic]` on a JS `Error`. WHY: these fields are not on the `js.Error` facade. WHY SAFE:
    * zero-cost view; every field is type-tested.
    */
  private def grpcError(e: js.Error): Option[SidecarError] =
    val d = e.asInstanceOf[js.Dynamic]
    (d.selectDynamic("code"): Any) match
      case code: Double if code.isWhole && code >= 1 && code < grpcNames.size =>
        val description = (d.selectDynamic("rawMessage"): Any) match
          case s: String => s
          case _         =>
            (d.selectDynamic("details"): Any) match
              case s: String => s
              case _         => e.message
        Some(SidecarError(None, connectReason(d), None, Some(grpcNames(code.toInt)), description))
      case _ => None

  /** `google.rpc.ErrorInfo.reason` from a Connect error's `details` (`[{type, value: Uint8Array}]`). ErrorInfo's field
    * 1 is `reason` (tag byte 0x0A, then a varint length — one byte for every reason daprd sends, all < 128 chars).
    */
  private def connectReason(d: js.Dynamic): Option[String] =
    (d.selectDynamic("details"): Any) match
      case arr: js.Array[?] =>
        arr.iterator
          .flatMap { item =>
            val detail = item.asInstanceOf[js.Dynamic]
            (detail.selectDynamic("type"): Any, detail.selectDynamic("value"): Any) match
              case ("google.rpc.ErrorInfo", bytes: js.typedarray.Uint8Array)
                  if bytes.length > 2 && bytes(0) == 0x0a && bytes(1) < 0x80 && bytes.length >= 2 + bytes(1) =>
                val len = bytes(1).toInt
                Some(new String((0 until len).map(i => bytes(2 + i).toChar).toArray))
              case _ => None
          }
          .nextOption()
      case _ => None

  /** Node's `fetch failed` hides the reason in `cause` (`ECONNREFUSED`, `ENOTFOUND`, ...). */
  private def causeMessage(e: js.Error): String =
    (e.asInstanceOf[js.Dynamic].selectDynamic("cause"): Any) match
      case c: js.Error => c.message
      case _           => e.message
