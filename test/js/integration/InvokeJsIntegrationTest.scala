//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** Scala.js (Wasm+JSPI) [[InvokeCapability]] integration suite: a thin shell over the shared [[InvokeScenarios]], run
  * against the in-process [[JsItServerApp]]'s invoke routes (hosted by [[ServerDaprJsItSuite]]) via the live sidecar.
  * The JVM twin [[InvokeItTest]] runs the very same scenarios.
  *
  * The first call retries: daprd reports healthy slightly before the app channel finishes warming up.
  */
@scala.caps.assumeSafe
class InvokeJsIntegrationTest extends FunSuite, InvokeScenarios, ServerDaprJsItSuite:

  protected def serverAppId: AppId = ServerAppId
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)

  test("invoke: echo roundtrip via the test server")(withDapr(echoRoundtrip))
  test("invoke: falsy body 0 reaches the handler via the raw-fetch fallback")(withDapr(falsyZeroBodyRoundtrips))
  test("invoke: derived EchoService facade calls the matching server routes")(withDapr(derivedEchoServiceFacade))
  test("invoke: invoking a non-existent app throws")(withDapr(nonexistentAppThrows))
