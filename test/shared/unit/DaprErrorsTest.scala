package dapr4s.test.unit

import dapr4s.*
import dapr4s.invoke.*
import dapr4s.internal.{DaprErrors, ErrorContext, InvokeSupport, SidecarError}
import dapr4s.given
import munit.FunSuite
import scala.collection.immutable.ArraySeq

/** The shared error-translation table ([[DaprErrors]]) and the shared typed-invoke helpers ([[InvokeSupport]]). Runs on
  * both platforms, so the JVM and Scala.js map the same sidecar answer to the same exception type.
  *
  * Inputs are the exact daprd v1.17.0 answers recorded in `wiki/dapr/dapr-error-codes.md` (probed against a live daprd
  * over both HTTP and gRPC).
  */
@scala.caps.assumeSafe
class DaprErrorsTest extends FunSuite:

  private def http(status: Int, code: String, message: String, reason: Option[String] = None): SidecarError =
    SidecarError(Some(code), reason, Some(status), None, message)

  private def grpc(status: String, message: String, reason: Option[String] = None): SidecarError =
    SidecarError(None, reason, None, Some(status), message)

  private def component(kind: DaprComponentKind, name: String): ErrorContext = ErrorContext.Component(kind, name)

  private def assertNotFound(ex: DaprException, kind: DaprComponentKind, name: String)(using munit.Location): Unit =
    ex match
      case e: ComponentNotFoundException =>
        assertEquals(e.componentKind, kind)
        assertEquals(e.name, name)
      case other => fail(s"expected ComponentNotFoundException($kind, $name), got $other")

  // ---- component not found: HTTP (error code) ----

  test("state store not found over HTTP (kit error) → ComponentNotFoundException(State)"):
    val err = http(400, "ERR_STATE_STORE_NOT_FOUND", "state store nostore is not found", Some("DAPR_STATE_NOT_FOUND"))
    assertNotFound(
      DaprErrors.translate(component(DaprComponentKind.State, "nostore"), err, null),
      DaprComponentKind.State,
      "nostore",
    )

  test("pubsub not found over HTTP answers 404 → ComponentNotFoundException(PubSub)"):
    val err = http(404, "ERR_PUBSUB_NOT_FOUND", "pubsub nopubsub is not found", Some("DAPR_PUBSUB_NOT_FOUND"))
    assertNotFound(
      DaprErrors.translate(component(DaprComponentKind.PubSub, "nopubsub"), err, null),
      DaprComponentKind.PubSub,
      "nopubsub",
    )

  test("secret store not found over HTTP answers 401 (not a permission error) → ComponentNotFoundException"):
    val err = http(401, "ERR_SECRET_STORE_NOT_FOUND", "failed finding secret store with key nosecret")
    assertNotFound(
      DaprErrors.translate(component(DaprComponentKind.Secrets, "nosecret"), err, null),
      DaprComponentKind.Secrets,
      "nosecret",
    )

  test("'not configured' (no component of the kind at all) is also ComponentNotFoundException"):
    for (kind, code, msg) <- List(
        (DaprComponentKind.Secrets, "ERR_SECRET_STORES_NOT_CONFIGURED", "secret store is not configured"),
        (
          DaprComponentKind.Configuration,
          "ERR_CONFIGURATION_STORE_NOT_CONFIGURED",
          "configuration stores not configured",
        ),
        (DaprComponentKind.Lock, "ERR_LOCK_STORE_NOT_CONFIGURED", "lock store is not configured"),
        (DaprComponentKind.Crypto, "ERR_CRYPTO_PROVIDERS_NOT_CONFIGURED", "crypto providers not configured"),
      )
    do assertNotFound(DaprErrors.translate(component(kind, "x"), http(500, code, msg), null), kind, "x")

  test("output binding not found: ERR_INVOKE_OUTPUT_BINDING with the 'couldn't find' text"):
    val err = http(
      500,
      "ERR_INVOKE_OUTPUT_BINDING",
      "error invoking output binding nobinding: couldn't find output binding nobinding",
    )
    assertNotFound(
      DaprErrors.translate(component(DaprComponentKind.Binding, "nobinding"), err, null),
      DaprComponentKind.Binding,
      "nobinding",
    )

  test("any other ERR_INVOKE_OUTPUT_BINDING failure stays DaprApiException"):
    val err = http(500, "ERR_INVOKE_OUTPUT_BINDING", "error invoking output binding b: connection refused")
    DaprErrors.translate(component(DaprComponentKind.Binding, "b"), err, null) match
      case e: DaprApiException => assertEquals(e.errorCode, "ERR_INVOKE_OUTPUT_BINDING")
      case other               => fail(s"expected DaprApiException, got $other")

  // ---- component not found: gRPC (no error code; reason or message only) ----

  test("state store not found over gRPC: INVALID_ARGUMENT + ErrorInfo reason"):
    val err = grpc("INVALID_ARGUMENT", "state store nostore is not found", Some("DAPR_STATE_NOT_FOUND"))
    assertNotFound(
      DaprErrors.translate(component(DaprComponentKind.State, "nostore"), err, null),
      DaprComponentKind.State,
      "nostore",
    )

  test("legacy gRPC errors without details are matched by daprd's exact message format"):
    for (kind, name, msg) <- List(
        (DaprComponentKind.Secrets, "nosecret", "failed finding secret store with key nosecret"),
        (DaprComponentKind.Lock, "nolock", "lock store nolock not found"),
        (DaprComponentKind.Conversation, "noconv", "failed finding conversation component noconv"),
        (DaprComponentKind.Crypto, "nocrypto", "crypto provider nocrypto not found"),
        (DaprComponentKind.Configuration, "nocfg", "configuration store nocfg not found"),
      )
    do assertNotFound(DaprErrors.translate(component(kind, name), grpc("INVALID_ARGUMENT", msg), null), kind, name)

  test("a not-found message naming ANOTHER component does not match"):
    val err = grpc("INVALID_ARGUMENT", "lock store other not found")
    assert(DaprErrors.translate(component(DaprComponentKind.Lock, "mine"), err, null).isInstanceOf[DaprApiException])

  test("a not-found code of another kind does not match"):
    val err = http(400, "ERR_STATE_STORE_NOT_FOUND", "state store s is not found")
    assert(DaprErrors.translate(component(DaprComponentKind.Lock, "s"), err, null).isInstanceOf[DaprApiException])

  // ---- permission denied ----

  test("secret scope denial (ERR_PERMISSION_DENIED, 403) → PermissionDeniedException"):
    val err = http(403, "ERR_PERMISSION_DENIED", "access denied by policy to get \"k\" from \"s\"")
    assert(
      DaprErrors.translate(component(DaprComponentKind.Secrets, "s"), err, null).isInstanceOf[PermissionDeniedException],
    )

  test("pub/sub topic scope (ERR_PUBSUB_FORBIDDEN) and gRPC PERMISSION_DENIED → PermissionDeniedException"):
    val forbidden =
      http(403, "ERR_PUBSUB_FORBIDDEN", "topic t is not allowed for app id a", Some("DAPR_PUBSUB_FORBIDDEN"))
    assert(
      DaprErrors
        .translate(component(DaprComponentKind.PubSub, "p"), forbidden, null)
        .isInstanceOf[PermissionDeniedException],
    )
    val viaGrpc = grpc("PERMISSION_DENIED", "access denied by workflow access policy")
    assert(DaprErrors.translate(ErrorContext.Runtime, viaGrpc, null).isInstanceOf[PermissionDeniedException])

  test("service-invocation ACL denial (ERR_DIRECT_INVOKE wrapping PermissionDenied) → PermissionDeniedException"):
    val err = http(
      403,
      "ERR_DIRECT_INVOKE",
      "failed to invoke, id: b, err: rpc error: code = PermissionDenied desc = access control policy has denied access",
    )
    assert(DaprErrors.translate(ErrorContext.App(AppId("b")), err, null).isInstanceOf[PermissionDeniedException])

  // ---- service invocation ----

  test("unknown app id (ERR_DIRECT_INVOKE, couldn't find service) → AppUnreachableException"):
    val err = http(500, "ERR_DIRECT_INVOKE", "failed to invoke, id: nope, err: couldn't find service: nope")
    DaprErrors.translate(ErrorContext.App(AppId("nope")), err, null) match
      case e: AppUnreachableException => assertEquals(e.appId, AppId("nope"))
      case other                      => fail(s"expected AppUnreachableException, got $other")

  test("isSidecarInvokeError: daprd's own errors yes, the app's answers no"):
    assert(DaprErrors.isSidecarInvokeError(http(500, "ERR_DIRECT_INVOKE", "failed to invoke, id: x, err: boom")))
    assert(DaprErrors.isSidecarInvokeError(http(403, "ERR_PERMISSION_DENIED", "denied")))
    // An app answering 403 / with its own error body is the app's answer, passed through to the caller.
    assert(!DaprErrors.isSidecarInvokeError(SidecarError(None, None, Some(403), None, "forbidden by the app")))
    assert(!DaprErrors.isSidecarInvokeError(http(400, "MY_APP_ERROR", "bad input")))

  // ---- generic ----

  test("everything else → DaprApiException with the most specific code (ERR code > reason > gRPC status)"):
    def code(err: SidecarError): String =
      DaprErrors.translate(ErrorContext.Runtime, err, null) match
        case e: DaprApiException => e.errorCode
        case other               => fail(s"expected DaprApiException, got $other")
    assertEquals(code(http(500, "ERR_ACTOR_INVOKE_METHOD", "boom", Some("R"))), "ERR_ACTOR_INVOKE_METHOD")
    assertEquals(code(grpc("INTERNAL", "boom", Some("DAPR_X"))), "DAPR_X")
    assertEquals(code(grpc("UNAVAILABLE", "connection refused")), "UNAVAILABLE")
    assertEquals(code(SidecarError(None, None, Some(502), None, "bad gateway")), "UNKNOWN")

  test("the platform exception is kept as the cause; message carries the sidecar text"):
    val cause = new RuntimeException("sdk")
    val ex = DaprErrors.translate(ErrorContext.Runtime, grpc("INTERNAL", "boom"), cause)
    assert(ex.getCause eq cause)
    assertEquals(ex.getMessage, "INTERNAL: boom")

  // ---- InvokeSupport ----

  private val app = AppId("target")
  private val method = InvokeMethodName("m")

  test("typed invoke: 2xx body decodes; empty body reaches the codec as null"):
    assertEquals(
      InvokeSupport.decodeTyped[Int](app, method, InvokeResponse(200, Map.empty, Charsets.encodeString("42"))),
      42,
    )
    assertEquals(
      InvokeSupport.decodeTyped[Option[Int]](app, method, InvokeResponse(204, Map.empty, ArraySeq.empty)),
      None,
    )

  test("typed invoke: non-2xx (3xx included) → InvokeHttpException with status and body"):
    for status <- List(302, 404, 500) do
      val body = Charsets.encodeString(s"""{"why":"$status"}""")
      val ex = intercept[InvokeHttpException](
        InvokeSupport.decodeTyped[Int](app, method, InvokeResponse(status, Map.empty, body)),
      )
      assertEquals(ex.status, status)
      assertEquals(ex.bodyText, s"""{"why":"$status"}""")
      assertEquals(ex.appId, app)
      assertEquals(ex.method, method)

  test("throwIfSidecarError: throws the translated daprd error, ignores the app's own non-2xx"):
    val unreachable = http(500, "ERR_DIRECT_INVOKE", "failed to invoke, id: target, err: couldn't find service: target")
    intercept[AppUnreachableException](InvokeSupport.throwIfSidecarError(app, 500, Some(unreachable)))
    InvokeSupport.throwIfSidecarError(app, 500, None) // the app's plain 500
    InvokeSupport.throwIfSidecarError(app, 200, Some(unreachable)) // 2xx is never an error

  test("invokePath: segments and query percent-encoded, '/' in the method kept"):
    val path = InvokeSupport.invokePath(
      AppId("my app"),
      InvokeMethodName("api/orders/a b"),
      Map(QueryParamName("q") -> QueryParamValue("x&y=z"), QueryParamName("é") -> QueryParamValue("")),
    )
    assertEquals(path, "/v1.0/invoke/my%20app/method/api/orders/a%20b?q=x%26y%3Dz&%C3%A9=")

  test("normaliseHeaders: names lower-cased, repeated headers joined"):
    val h = InvokeSupport.normaliseHeaders(List("X-A" -> "1", "x-a" -> "2", "Content-Type" -> "text/plain"))
    assertEquals(h.get(MetadataKey("x-a")), Some(MetadataValue("1, 2")))
    val resp = InvokeResponse(200, h, ArraySeq.empty)
    assertEquals(resp.header("CONTENT-TYPE"), Some(MetadataValue("text/plain")))
    assert(resp.isSuccess)
    assert(!resp.copy(status = 301).isSuccess)

  test("invokeHttp companion forwarders reach the capability in scope"):
    val rec = RecordingInvoker("pong")
    given InvokeCapability = rec
    val out = InvokeCapability.invokeHttp(app, method, Charsets.encodeString("hi"), HttpMethod.Put)
    assertEquals(out.bodyText, "pong")
    given InvokeCapability.Instance = InvokeCapability.instance(app)
    InvokeCapability.Instance.invokeHttp(method, query = Map(QueryParamName("a") -> QueryParamValue("b")))
    assertEquals(rec.calls.toList, List("http|target|m|2|Put|0|0", "http|target|m|0|Post|0|1"))
