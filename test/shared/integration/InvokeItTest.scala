package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterState, EchoService, IncrRequest}
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.InvokeCapability]] integration suite — a SINGLE cross-platform file: the caller side of service invocation
  * against the union app server that registers the `echo`, `echo-int` and `double` routes. The bring-up and the two
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
  test("invoke: invoking a non-existent app throws")(withDapr(nonexistentAppThrows))

  def echoRoundtrip(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[AccessInvokeCapability](serverAppId)
      val resp = retrying("echo")(api.invoke(InvokeMethodName("echo"), "hello")[String])
      assertEquals(resp, "hello")

  def falsyZeroBodyRoundtrips(using DaprCapability): Unit =
    // Exercises the raw-fetch fallback in the JS client (the SDK drops JS-falsy request bodies).
    DaprCapability.invoke:
      val api = summon[AccessInvokeCapability](serverAppId)
      val resp = retrying("echo-int")(api.invoke(InvokeMethodName("echo-int"), 0)[Int])
      assertEquals(resp, 0)

  def derivedEchoServiceFacade(using DaprCapability): Unit =
    DaprCapability.invoke:
      val service = EchoService(serverAppId)
      assertEquals(retrying("derived-echo")(service.echo("derived")), "derived")
      assertEquals(service.double(IncrRequest(21)), CounterState(42))

  def nonexistentAppThrows(using DaprCapability): Unit =
    DaprCapability.invoke:
      val api = summon[AccessInvokeCapability](AppId("no-such-app"))
      val attempt =
        scala.util.Try(api.invoke(InvokeMethodName("method"), "data")[String])
      assert(attempt.isFailure, s"invoking a non-existent app should throw, got: $attempt")
