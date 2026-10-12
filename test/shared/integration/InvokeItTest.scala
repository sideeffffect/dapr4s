package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterState, EchoService, IncrRequest}
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[InvokeCapability]] integration suite — a SINGLE cross-platform file: the caller side of service invocation against
  * the union app server that registers the `echo`, `echo-int`, `double` and `fail` routes. The bring-up and the two
  * hooks the bodies use — `serverAppId` and `retrying` — come from `ServerDaprItSuite`, a trait with one implementation
  * per platform under the same name (sidecar-first host server hosting [[dapr4s.test.integration.apps.itUnionApp]] on
  * both platforms). `retrying` wraps the first sidecar call: the app channel warms up slightly after daprd reports
  * healthy.
  */
@scala.caps.assumeSafe
class InvokeItTest extends FunSuite, ServerDaprItSuite:

  test("invoke: echo roundtrip")(withDapr(echoRoundtrip))
  test("invoke: falsy body 0 reaches the handler")(withDapr(falsyZeroBodyRoundtrips))
  test("invoke: derived EchoService facade calls the matching server routes")(withDapr(derivedEchoServiceFacade))
  test("invoke: invoking a non-existent app throws AppUnreachableException")(withDapr(nonexistentAppThrows))
  test("invoke: a route answering 500 throws InvokeHttpException with status and body")(withDapr(non2xxThrows))
  test("invokeHttp: returns status, headers and body of a 2xx answer")(withDapr(invokeHttpOk))
  test("invokeHttp: returns non-2xx answers as values")(withDapr(invokeHttpNon2xx))

  def echoRoundtrip(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[InvokeCapability].instance(serverAppId)
      val resp = retrying("echo")(api.invoke(InvokeMethodName("echo"), "hello")[String])
      assertEquals(resp, "hello")

  def falsyZeroBodyRoundtrips(using DaprCapability): Unit =
    // Exercises the raw-fetch fallback in the JS client (the SDK drops JS-falsy request bodies).
    DaprCapability.invoke:
      val api = summon[InvokeCapability].instance(serverAppId)
      val resp = retrying("echo-int")(api.invoke(InvokeMethodName("echo-int"), 0)[Int])
      assertEquals(resp, 0)

  def derivedEchoServiceFacade(using DaprCapability): Unit =
    DaprCapability.invoke:
      val service = EchoService(serverAppId)
      assertEquals(retrying("derived-echo")(service.echo("derived")), "derived")
      assertEquals(service.double(IncrRequest(21)), CounterState(42))

  def nonexistentAppThrows(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[InvokeCapability].instance(AppId("no-such-app"))
      val typed = intercept[AppUnreachableException](api.invoke(InvokeMethodName("method"), "data")[String])
      assertEquals(typed.appId, AppId("no-such-app"))
      // invokeHttp returns the target's non-2xx answers, but an unreachable target is no answer at all.
      val raw = intercept[AppUnreachableException](api.invokeHttp(InvokeMethodName("method")))
      assertEquals(raw.appId, AppId("no-such-app"))

  def non2xxThrows(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[InvokeCapability].instance(serverAppId)
      retrying("echo")(api.invoke(InvokeMethodName("echo"), "warm-up")[String]): Unit
      val ex = intercept[InvokeHttpException](api.invoke(InvokeMethodName("fail"), "it")[String])
      assertEquals(ex.status, 500)
      assertEquals(ex.appId, serverAppId)
      assertEquals(ex.method, InvokeMethodName("fail"))
      assert(ex.bodyText.contains("refusing it"), s"the app's error body is kept, got: ${ex.bodyText}")
      // An unknown route is the app's own 404 — also an InvokeHttpException, not a sidecar error.
      assertEquals(intercept[InvokeHttpException](api.invoke[String](InvokeMethodName("no-such-route"))).status, 404)

  def invokeHttpOk(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[InvokeCapability].instance(serverAppId)
      val resp = retrying("echo") {
        api.invokeHttp(
          InvokeMethodName("echo"),
          Charsets.encodeString("\"raw\""),
          HttpMethod.Post,
          Map(MetadataKey("Content-Type") -> MetadataValue("application/json")),
          Map(QueryParamName("q") -> QueryParamValue("a b")),
        )
      }
      assertEquals(resp.status, 200)
      assertEquals(resp.bodyText, "\"raw\"")
      assert(
        resp.header("content-type").exists(_.value.startsWith("application/json")),
        s"expected the app's JSON content type, got headers: ${resp.headers}",
      )

  def invokeHttpNon2xx(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[InvokeCapability].instance(serverAppId)
      retrying("echo")(api.invoke(InvokeMethodName("echo"), "warm-up")[String]): Unit
      val failed = api.invokeHttp(
        InvokeMethodName("fail"),
        Charsets.encodeString("\"x\""),
        headers = Map(MetadataKey("Content-Type") -> MetadataValue("application/json")),
      )
      assertEquals(failed.status, 500)
      assert(failed.bodyText.contains("refusing x"), s"got: ${failed.bodyText}")
      assertEquals(InvokeCapability.invokeHttp(serverAppId, InvokeMethodName("no-such-route")).status, 404)
