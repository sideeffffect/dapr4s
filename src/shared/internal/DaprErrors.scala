package dapr4s.internal

import dapr4s.*

/** What a failed sidecar call was addressing. Every call site in the two `internal` walls states it, so the translation
  * to a [[DaprException]] takes the component name / app id from the '''request''' rather than parsing it out of the
  * sidecar's error message (message formats differ per building block and per protocol, and are not an API).
  */
private[dapr4s] enum ErrorContext:
  /** A call that addresses one named component (state store, pub/sub, secret store, ...). */
  case Component(kind: DaprComponentKind, name: String)

  /** A service-invocation call to `appId`. */
  case App(appId: AppId)

  /** A call with no addressable component: actors, workflows, jobs. */
  case Runtime

/** A sidecar error, normalised from whatever the platform transport delivered (Java SDK `DaprException` / gRPC
  * `StatusRuntimeException` on the JVM; the JS SDK's JSON-in-`Error.message` convention, a gRPC `ServiceError`, or a
  * raw `fetch` response on Scala.js). Each platform wall builds one of these; [[DaprErrors.translate]] decides the
  * type.
  *
  * @param errorCode
  *   the Dapr error code (`ERR_STATE_STORE_NOT_FOUND`, ...) — present on HTTP error bodies, absent on gRPC
  * @param reason
  *   the `google.rpc.ErrorInfo` reason (`DAPR_STATE_NOT_FOUND`, or an `ERR_*` code for `apierrors.Basic` errors) —
  *   present only on the newer "kit" errors, on both protocols
  * @param httpStatus
  *   the HTTP status, when the error came over HTTP
  * @param grpcStatus
  *   the gRPC status name (`INVALID_ARGUMENT`, `PERMISSION_DENIED`, ...), when the error came over gRPC
  * @param message
  *   the sidecar's raw error message (the gRPC status description / the HTTP body's `message`), without any SDK prefix
  */
private[dapr4s] final case class SidecarError(
    errorCode: Option[String],
    reason: Option[String],
    httpStatus: Option[Int],
    grpcStatus: Option[String],
    message: String,
)

/** The single, platform-independent decision table from a [[SidecarError]] to a [[DaprException]] subtype.
  *
  * Both walls call it — the JVM from `MonoOps.awaitResult` / `JvmErrors`, Scala.js from `JsAwait` / `JsErrors` — so the
  * same sidecar answer becomes the same exception type on every platform. The table and the daprd v1.17 sources it was
  * read from are recorded in `wiki/dapr/dapr-error-codes.md`.
  *
  * Matching uses the error code (HTTP) or the `ErrorInfo` reason (kit errors, both protocols) first. Legacy errors over
  * gRPC carry neither (daprd's `messages.APIError.GRPCStatus()` sends only the status code and the message), so the
  * fallback compares the message against daprd's exact format strings, instantiated with the name from the
  * [[ErrorContext]] — never a free-form "contains not found" guess.
  */
private[dapr4s] object DaprErrors:

  /** Error codes / ErrorInfo reasons that mean "no such component" (or "no component of this kind at all"), per kind.
    * Source: dapr v1.17.0 `pkg/messages/predefined.go`, `pkg/api/errors/{state,pubsub}.go`, `pkg/api/grpc/grpc.go`.
    */
  private val notFoundCodes: Map[DaprComponentKind, Set[String]] = Map(
    DaprComponentKind.State -> Set(
      "ERR_STATE_STORE_NOT_FOUND",
      "ERR_STATE_STORE_NOT_CONFIGURED",
      "DAPR_STATE_NOT_FOUND",
      "DAPR_STATE_NOT_CONFIGURED",
    ),
    DaprComponentKind.PubSub -> Set(
      "ERR_PUBSUB_NOT_FOUND",
      "ERR_PUBSUB_NOT_CONFIGURED",
      "DAPR_PUBSUB_NOT_FOUND",
      "DAPR_PUBSUB_NOT_CONFIGURED",
    ),
    DaprComponentKind.Secrets -> Set("ERR_SECRET_STORE_NOT_FOUND", "ERR_SECRET_STORES_NOT_CONFIGURED"),
    DaprComponentKind.Configuration -> Set(
      "ERR_CONFIGURATION_STORE_NOT_FOUND",
      "ERR_CONFIGURATION_STORE_NOT_CONFIGURED",
    ),
    DaprComponentKind.Lock -> Set("ERR_LOCK_STORE_NOT_FOUND", "ERR_LOCK_STORE_NOT_CONFIGURED"),
    DaprComponentKind.Crypto -> Set("ERR_CRYPTO_PROVIDER_NOT_FOUND", "ERR_CRYPTO_PROVIDERS_NOT_CONFIGURED"),
    DaprComponentKind.Conversation -> Set("ERR_CONVERSATION_NOT_FOUND"),
    // Bindings have no dedicated code: a missing output binding is ERR_INVOKE_OUTPUT_BINDING with the inner text
    // "couldn't find output binding <name>" — matched by message only (see notFoundMessages).
    DaprComponentKind.Binding -> Set.empty,
  )

  /** daprd's exact "not found" / "not configured" messages for the component `name`, per kind (same sources as
    * [[notFoundCodes]]). Used for legacy errors over gRPC, which carry no code; harmless as a second check on HTTP.
    */
  private def notFoundMessages(kind: DaprComponentKind, name: String): List[String] =
    kind match
      case DaprComponentKind.State   => List(s"state store $name is not found", s"state store $name is not configured")
      case DaprComponentKind.PubSub  => List(s"pubsub $name is not found", s"pubsub $name is not configured")
      case DaprComponentKind.Secrets =>
        List(s"failed finding secret store with key $name", "secret store is not configured")
      case DaprComponentKind.Configuration =>
        List(s"configuration store $name not found", "configuration stores not configured")
      case DaprComponentKind.Lock         => List(s"lock store $name not found", "lock store is not configured")
      case DaprComponentKind.Crypto       => List(s"crypto provider $name not found", "crypto providers not configured")
      case DaprComponentKind.Conversation => List(s"failed finding conversation component $name")
      case DaprComponentKind.Binding      => List(s"couldn't find output binding $name")

  /** Codes / reasons that mean "denied by policy". Source: predefined.go `ErrPermissionDenied`,
    * `pkg/api/errors/pubsub.go` `PubSubTopicForbidden`.
    */
  private val permissionDeniedCodes: Set[String] =
    Set("ERR_PERMISSION_DENIED", "ERR_PUBSUB_FORBIDDEN", "DAPR_PUBSUB_FORBIDDEN")

  /** The most specific code available, for [[DaprApiException.errorCode]]. */
  def bestCode(err: SidecarError): String =
    err.errorCode.orElse(err.reason).orElse(err.grpcStatus).getOrElse("UNKNOWN")

  private def codes(err: SidecarError): Set[String] = err.errorCode.toSet ++ err.reason.toSet

  private def isPermissionDenied(err: SidecarError): Boolean =
    codes(err).exists(permissionDeniedCodes) ||
      err.grpcStatus.contains("PERMISSION_DENIED") ||
      err.httpStatus.contains(403) ||
      // Service-invocation ACL denials arrive wrapped: ERR_DIRECT_INVOKE whose message embeds the callee's
      // gRPC status ("... err: rpc error: code = PermissionDenied desc = access control policy has denied ...").
      // Only under ERR_DIRECT_INVOKE: a component wrapping its cloud backend's gRPC denial is not a Dapr policy.
      (codes(err).contains("ERR_DIRECT_INVOKE") && err.message.contains("code = PermissionDenied"))

  private def isComponentNotFound(kind: DaprComponentKind, name: String, err: SidecarError): Boolean =
    codes(err).exists(notFoundCodes.getOrElse(kind, Set.empty)) ||
      notFoundMessages(kind, name).exists(err.message.contains)

  /** Service invocation failed before reaching the target app's handler. daprd reports every such failure as
    * ERR_DIRECT_INVOKE with the message format "failed to invoke, id: %s, err: %v" (predefined.go `ErrDirectInvoke`):
    * unknown app id ("couldn't find service: X" from mDNS), no healthy instance, connection refused, ...
    */
  private def isAppUnreachable(err: SidecarError): Boolean =
    (codes(err).contains("ERR_DIRECT_INVOKE") || err.message.startsWith("failed to invoke, id:")) &&
      // A resiliency/deadline timeout means the app WAS reached but did not answer in time: not "unreachable".
      !err.message.contains("deadline exceeded")

  /** Decide the [[DaprException]] for `err` raised by a call addressing `ctx`. `cause` is the platform exception, kept
    * for logging. Order: permission denied (any context) → component not found → app unreachable → generic.
    */
  def translate(ctx: ErrorContext, err: SidecarError, cause: Throwable | Null): DaprException =
    if isPermissionDenied(err) then PermissionDeniedException(s"${bestCode(err)}: ${err.message}", cause)
    else
      ctx match
        case ErrorContext.Component(kind, name) if isComponentNotFound(kind, name, err) =>
          ComponentNotFoundException(kind, name, err.message, cause)
        case ErrorContext.App(appId) if isAppUnreachable(err) =>
          AppUnreachableException(appId, err.message, cause)
        case _ =>
          DaprApiException(bestCode(err), err.message, cause)

  /** Failure to reach the sidecar at all (connection refused, DNS, reset). Same code on every platform. */
  def unavailable(message: String, cause: Throwable | Null): DaprException =
    DaprApiException("UNAVAILABLE", message, cause)

  /** Is a service-invocation response with HTTP `status` and parsed error body `err` a '''sidecar''' error rather than
    * the target app's own answer?
    *
    * daprd passes the app's status, headers and body through unchanged (`directmessaging.go`), and reports its own
    * failures as a JSON `{"errorCode": "ERR_...", "message": ...}` body. The two cannot be told apart on the wire with
    * certainty — an app could answer with exactly that body — so the rule is: a non-2xx response whose body parses to a
    * Dapr error whose code is ERR_DIRECT_INVOKE (unreachable target, ACL denial) or a permission-denied code is the
    * sidecar's. Any other non-2xx response — a bare 403 from the app, or an app that forwards some other Dapr error
    * body — is the app's answer.
    */
  def isSidecarInvokeError(err: SidecarError): Boolean =
    codes(err).exists(c => c == "ERR_DIRECT_INVOKE" || permissionDeniedCodes(c))
