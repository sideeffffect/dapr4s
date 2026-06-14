package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.InvokeCapability]] integration suite — a SINGLE cross-platform file over the shared [[InvokeScenarios]].
  * The bring-up and the scenario hooks (`serverAppId` / `retrying` / `withDapr`) come from `InvokeHarness`, a trait
  * with one implementation per platform under the same name (a two-phase host server + testcontainers sidecar on the
  * JVM; the in-process union server on Scala.js), so each platform build links its own and this one file is the suite
  * on both.
  */
@scala.caps.assumeSafe
class InvokeItTest extends FunSuite, InvokeHarness, InvokeScenarios:

  test("invoke: echo roundtrip")(withDapr(echoRoundtrip))
  test("invoke: falsy body 0 reaches the handler")(withDapr(falsyZeroBodyRoundtrips))
  test("invoke: derived EchoService facade calls the matching server routes")(withDapr(derivedEchoServiceFacade))
  test("invoke: invoking a non-existent app throws")(withDapr(nonexistentAppThrows))
