package dapr4s

import dapr4s.state.*
import dapr4s.invoke.*
import scala.collection.immutable.ArraySeq

/** Thrown by [[dapr4s.state.StateCapability.saveWithETag]] and [[dapr4s.state.StateCapability.deleteWithETag]] when the
  * provided ETag does not match the current server-side value, indicating a concurrent modification.
  *
  * Callers should re-read the key to get the current value and ETag, then retry the operation.
  */
@scala.caps.assumeSafe
final class ETagMismatchException(key: StateStoreKey, etag: ETag)
    extends Exception(s"ETag mismatch for key '${key.value}' (provided: ${etag.value})")

/** Thrown by [[JsonCodec.decodeOrThrow]] and internally by capability implementations when a JSON payload cannot be
  * parsed into the expected type.
  *
  * Indicates either a malformed JSON string or a type mismatch between the payload and the target Scala type.
  */
@scala.caps.assumeSafe
final class JsonDecodeException(message: String, cause: Exception | Null = null) extends Exception(message, cause)

/** The kind of Dapr component a [[ComponentNotFoundException]] refers to — one case per building block whose calls
  * address a named component.
  *
  * Workflow, jobs and actors are not listed: dapr4s never names a component for them (the workflow engine is the
  * built-in `dapr` engine, jobs go to the scheduler, actors to placement), so the sidecar cannot report one missing.
  */
enum DaprComponentKind:
  case State, PubSub, Secrets, Configuration, Lock, Binding, Crypto, Conversation

/** Every error the Dapr sidecar (or the path to it) reports to dapr4s code surfaces as a subclass of this type, on
  * every platform. No SDK exception (`io.dapr.exceptions.DaprException`, `io.grpc.StatusRuntimeException`, a
  * `js.JavaScriptException` wrapping an SDK error) reaches user code; the original is kept as the [[getCause]] for
  * logging only — its type is platform-specific and not part of the API.
  *
  * Values that are an expected outcome, not an error, keep their dedicated shapes and are NOT thrown as a
  * `DaprException`: a missing state key is `None`, an ETag conflict is `Some(ETagMismatchException)`, lock outcomes are
  * `Boolean`/`UnlockStatus`, a bulk-publish partial failure is a `BulkPublishResult`.
  *
  * The subclasses are the recoverable cases a caller can act on; everything else is a [[DaprApiException]] carrying the
  * sidecar's error code:
  *   - [[ComponentNotFoundException]] — the named component does not exist (or no component of that kind does);
  *   - [[PermissionDeniedException]] — an access-control policy or scope denied the call;
  *   - [[AppUnreachableException]] — service invocation could not reach the target app;
  *   - [[InvokeHttpException]] — the target app answered a typed `invoke` with a non-2xx status;
  *   - [[DaprApiException]] — any other sidecar or transport error.
  *
  * The mapping from Dapr's error codes to these types is in `wiki/dapr/dapr-error-codes.md`.
  *
  * WHY `@scala.caps.assumeSafe` (on this class and every subclass): the classes are thrown from inside the `internal`
  * walls and caught in safe-mode user code; `Exception` is a Java class the safe-mode checker cannot verify. The
  * subclasses hold only immutable values (strings, opaque ids, an `ArraySeq`), so no capability can escape through
  * them. Same reasoning as [[ETagMismatchException]].
  */
@scala.caps.assumeSafe
sealed abstract class DaprException(message: String, cause: Throwable | Null) extends Exception(message, cause)

/** The sidecar has no component named `name` of kind `componentKind` (or no component of that kind at all). Usually a
  * deployment error: a missing or misnamed component YAML, or a component that failed to initialise.
  *
  * @param componentKind
  *   the building block the call addressed
  * @param name
  *   the component name the call addressed (taken from the call, not parsed from the sidecar message)
  * @param detail
  *   the sidecar's own error message, for diagnostics
  */
@scala.caps.assumeSafe
final class ComponentNotFoundException(
    val componentKind: DaprComponentKind,
    val name: String,
    detail: String = "",
    cause: Throwable | Null = null,
) extends DaprException(
      s"$componentKind component '$name' not found" + (if detail.isEmpty then "" else s": $detail"),
      cause,
    )

/** The sidecar refused the call: an access-control policy (service invocation), a component scope or a pub/sub topic
  * scope does not allow this app to make it.
  */
@scala.caps.assumeSafe
final class PermissionDeniedException(message: String, cause: Throwable | Null = null)
    extends DaprException(message, cause)

/** Service invocation could not reach `appId`: the name resolver does not know the app id, or the target is not healthy
  * / not listening. Raised by both [[dapr4s.invoke.InvokeCapability.Instance.invoke]] and
  * [[dapr4s.invoke.InvokeCapability.Instance.invokeHttp]].
  *
  * @param detail
  *   the sidecar's own error message, for diagnostics
  */
@scala.caps.assumeSafe
final class AppUnreachableException(val appId: AppId, detail: String = "", cause: Throwable | Null = null)
    extends DaprException(
      s"app '${appId.value}' is unreachable" + (if detail.isEmpty then "" else s": $detail"),
      cause,
    )

/** The target app answered a typed [[dapr4s.invoke.InvokeCapability.Instance.invoke]] with a non-2xx HTTP status. The
  * status and the raw response body are kept, so a caller can branch on them. (Use
  * [[dapr4s.invoke.InvokeCapability.Instance.invokeHttp]] to receive non-2xx responses as values instead.)
  *
  * @param body
  *   the response body exactly as the app sent it; [[bodyText]] decodes it as UTF-8
  */
@scala.caps.assumeSafe
final class InvokeHttpException(
    val appId: AppId,
    val method: InvokeMethodName,
    val status: Int,
    val body: ArraySeq[Byte],
) extends DaprException(
      s"invoke ${appId.value}/${method.value} answered HTTP $status" +
        (if body.isEmpty then "" else s": ${Charsets.decodeString(body, Charsets.Utf8)}"),
      null,
    ):
  /** The response body decoded as UTF-8. */
  def bodyText: String = Charsets.decodeString(body, Charsets.Utf8)

/** Any other error the sidecar (or the transport to it) reported.
  *
  * @param errorCode
  *   the most specific code available, in this order: the Dapr error code (`ERR_STATE_GET`, ...) from an HTTP error
  *   body; the `google.rpc.ErrorInfo` reason (`DAPR_STATE_...`) from gRPC error details; the gRPC status name
  *   (`INVALID_ARGUMENT`, `UNAVAILABLE`, ...); `UNAVAILABLE` for a failure to reach the sidecar at all; else `UNKNOWN`.
  */
@scala.caps.assumeSafe
final class DaprApiException(val errorCode: String, message: String, cause: Throwable | Null = null)
    extends DaprException(s"$errorCode: $message", cause)
