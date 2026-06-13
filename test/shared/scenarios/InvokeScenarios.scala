package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterState, EchoService, IncrRequest}
import munit.Assertions
import unsafeExceptions.canThrowAny

/** Direct-call [[InvokeCapability]] scenarios shared by the JVM and JS integration suites: the caller side of service
  * invocation against an app server that registers the `echo`, `echo-int` and `double` routes (the JVM in-test
  * [[dapr4s.internal.DaprAppServer]] / the JS `JsItServerApp`).
  *
  * Two hooks the platforms supply, because the server bring-up genuinely differs:
  *   - [[serverAppId]] — the app id the harness registered the routes under;
  *   - [[retrying]] — wraps the first sidecar call: the JS app channel warms up slightly after daprd reports healthy
  *     (the JVM polls sidecar health up front), so JS supplies `retryUntilSuccess` and the JVM supplies identity.
  */
trait InvokeScenarios:
  self: Assertions =>

  protected def serverAppId: AppId
  protected def retrying[T](label: String)(body: => T): T

  def echoRoundtrip(using DaprCapability): Unit =
    DaprCapability.invoke:
      val resp = retrying("echo")(InvokeCapability.invoke(serverAppId, InvokeMethodName("echo"), "hello")[String])
      assertEquals(resp, "hello")

  def falsyZeroBodyRoundtrips(using DaprCapability): Unit =
    // Exercises the raw-fetch fallback in the JS client (the SDK drops JS-falsy request bodies).
    DaprCapability.invoke:
      val resp = retrying("echo-int")(InvokeCapability.invoke(serverAppId, InvokeMethodName("echo-int"), 0)[Int])
      assertEquals(resp, 0)

  def derivedEchoServiceFacade(using DaprCapability): Unit =
    DaprCapability.invoke:
      val service = EchoService(serverAppId)
      assertEquals(retrying("derived-echo")(service.echo("derived")), "derived")
      assertEquals(service.double(IncrRequest(21)), CounterState(42))

  def nonexistentAppThrows(using DaprCapability): Unit =
    DaprCapability.invoke:
      val attempt =
        scala.util.Try(InvokeCapability.invoke(AppId("no-such-app"), InvokeMethodName("method"), "data")[String])
      assert(attempt.isFailure, s"invoking a non-existent app should throw, got: $attempt")
