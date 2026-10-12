//> using target.platform "jvm"
package dapr4s.internal

import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import NullOps.*

/** The JVM wall's single translation point from Java SDK / gRPC / raw-HTTP failures to [[dapr4s.DaprException]].
  *
  * Every sidecar call in `src/jvm/internal/` reaches it, through one of three doors:
  *   - [[MonoOps.awaitResult]] (and [[FluxOps.collectBytes]]) for the `Mono`/`Flux` SDK API — almost every call;
  *   - [[guard]] for the SDK clients that throw synchronously (`DaprWorkflowClient`);
  *   - [[fromHttp]] for the raw-HTTP paths (service invocation, actor state from inside an actor).
  *
  * The decision of WHICH exception to throw is not made here: this object only normalises the platform failure into a
  * [[SidecarError]] and hands it to the shared [[DaprErrors.translate]], so the JVM and Scala.js agree.
  */
@scala.caps.assumeSafe
private[internal] object JvmErrors:

  /** Map `t` to a [[dapr4s.DaprException]] if it is a sidecar or transport failure; return it unchanged otherwise
    * (codec errors, SDK argument validation, programming errors — those are not sidecar errors).
    */
  def translate(ctx: ErrorContext, t: Throwable): Throwable =
    t match
      case d: dapr4s.DaprException => d
      case _                       =>
        grpcStatusIn(t) match
          case Some(sre) => DaprErrors.translate(ctx, fromGrpc(sre), t)
          case None      =>
            t match
              case d: io.dapr.exceptions.DaprException => DaprErrors.translate(ctx, fromSdkHttp(d), d)
              case io: java.io.IOException             => DaprErrors.unavailable(String.valueOf(io.getMessage).nn, io)
              case other                               => other

  /** Run a synchronous SDK call (one that throws instead of returning a `Mono`) and translate its failure.
    *
    * Catches only `NonFatal`, so `InterruptedException` (and every fatal error) propagates untouched — the interrupt
    * contract documented on [[MonoOps.awaitResult]] holds here too. Rethrowing requires a `CanThrow`, supplied the same
    * way `awaitResult` does it (`unsafeExceptions.canThrowAny`): this method is the documented propagation point, the
    * capability-tracking of exceptions is not used for Dapr errors.
    */
  def guard[A](ctx: ErrorContext)(body: => A): A =
    given CanThrow[Exception] = unsafeExceptions.canThrowAny
    try body
    catch case NonFatal(e) => throw translate(ctx, e)

  /** A non-2xx answer from a raw sidecar HTTP call, body included. */
  def fromHttp(ctx: ErrorContext, status: Int, body: String): dapr4s.DaprException =
    val parsed = parseErrorBody(status, body)
    DaprErrors.translate(ctx, parsed.getOrElse(SidecarError(None, None, Some(status), None, body)), null)

  /** Parse an HTTP body as daprd's `{"errorCode": ..., "message": ..., "details": [{"reason": ...}]}` error shape.
    * `None` when the body is not JSON or has no string `errorCode`.
    */
  def parseErrorBody(status: Int, body: String): Option[SidecarError] =
    try
      val node = Json.mapper.readTree(body)
      if node == null || !node.isObject then None
      else
        textField(node, "errorCode").map { code =>
          SidecarError(
            errorCode = Some(code),
            reason = reasonFromDetails(node.get("details")),
            httpStatus = Some(status),
            grpcStatus = None,
            message = textField(node, "message").getOrElse(""),
          )
        }
    catch case NonFatal(_) => None

  private def textField(node: JsonNode, field: String): Option[String] =
    node.get(field).toOption.filter(_.isTextual).map(_.asText().nn)

  private def reasonFromDetails(details: JsonNode | Null): Option[String] =
    details.toOption.filter(_.isArray).flatMap { arr =>
      arr.elements().nn.asScala.flatMap(d => textField(d, "reason")).nextOption()
    }

  /** Find a gRPC status exception in `t`'s cause chain (the SDK wraps it in its `DaprException`; the workflow client
    * throws it bare). Bounded walk, so a cyclic cause chain cannot loop.
    */
  private def grpcStatusIn(t: Throwable): Option[Throwable] =
    Iterator
      .iterate[Throwable | Null](t)(e => if e == null then null else e.getCause)
      .take(16)
      .collectFirst {
        case e: io.grpc.StatusRuntimeException => e
        case e: io.grpc.StatusException        => e
      }

  private def fromGrpc(e: Throwable): SidecarError =
    val status = io.grpc.Status.fromThrowable(e).nn
    val reason =
      try
        io.grpc.protobuf.StatusProto
          .fromThrowable(e)
          .toOption
          .flatMap { proto =>
            proto.getDetailsList.nn.asScala
              .find(_.is(classOf[com.google.rpc.ErrorInfo]))
              .map(_.unpack(classOf[com.google.rpc.ErrorInfo]).nn.getReason.nn)
              .filter(_.nonEmpty)
          }
      catch case NonFatal(_) => None // malformed details must not hide the original error
    SidecarError(
      errorCode = None,
      reason = reason,
      httpStatus = None,
      grpcStatus = Some(status.getCode.nn.name),
      message = status.getDescription.toOption.getOrElse(""),
    )

  /** A Java SDK `DaprException` from its HTTP path (no gRPC status in the chain). The SDK keeps the raw body as the
    * payload; parse it for the real message and ErrorInfo reason, falling back to the SDK's own fields.
    */
  private def fromSdkHttp(d: io.dapr.exceptions.DaprException): SidecarError =
    val status = d.getHttpStatusCode
    val payload = d.getPayload.toOption.map(p => new String(p, java.nio.charset.StandardCharsets.UTF_8))
    payload.flatMap(parseErrorBody(status, _)).getOrElse {
      SidecarError(
        errorCode = d.getErrorCode.toOption.filter(_.nonEmpty),
        reason = None,
        httpStatus = Option.when(status > 0)(status),
        grpcStatus = None,
        message = d.getMessage.toOption.getOrElse(""),
      )
    }
