//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** Scala.js (Wasm+JSPI) [[InvokeCapability]] integration suite: a thin shell over the shared [[InvokeScenarios]], run
  * against the JS test server's invoke routes ([[JsItServerApp]]) via the live sidecar. The JVM twin [[InvokeItTest]]
  * runs the very same scenarios.
  *
  * The first call retries: daprd reports healthy slightly before the app channel finishes warming up.
  */
@scala.caps.assumeSafe
class InvokeJsIntegrationTest extends FunSuite, InvokeScenarios:

  override def munitTimeout: Duration = 120.seconds

  protected def serverAppId: AppId = ServerAppId
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)

  private def run(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async(Dapr(clientConfig).run(body)).toFuture

  test("invoke: echo roundtrip via the test server")(run(echoRoundtrip))
  test("invoke: falsy body 0 reaches the handler via the raw-fetch fallback")(run(falsyZeroBodyRoundtrips))
  test("invoke: derived EchoService facade calls the matching server routes")(run(derivedEchoServiceFacade))
  test("invoke: invoking a non-existent app throws")(run(nonexistentAppThrows))
