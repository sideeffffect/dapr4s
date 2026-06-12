//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.{CounterState, EchoService, IncrRequest}
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[InvokeCapability]] end to end through the sidecar to the JS test server's invoke routes ([[JsItServerApp]]) — the
  * Scala.js twin of [[InvokeCapabilityServerTest]], including the derived [[EchoService]] caller facade.
  *
  * The falsy-`0` test exercises the raw-fetch fallback in `InvokeCapabilityImpl` (the JS SDK silently drops JS-falsy
  * request bodies — `if (params?.body)` in HTTPClient.js).
  *
  * The first call retries: daprd reports healthy slightly before the app channel finishes warming up, mirroring the
  * startup polling the JVM twins do.
  */
@scala.caps.assumeSafe
class InvokeJsIntegrationTest extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  test("invoke: echo roundtrip via the test server"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.invoke {
          val resp = retryUntilSuccess("echo through app channel") {
            InvokeCapability.invoke(ServerAppId, InvokeMethodName("echo"), "hello-js")[String]
          }
          assertEquals(resp, "hello-js")
        }
    }.toFuture

  test("invoke: falsy body 0 reaches the handler via the raw-fetch fallback"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.invoke {
          val resp = retryUntilSuccess("echo-int through app channel") {
            InvokeCapability.invoke(ServerAppId, InvokeMethodName("echo-int"), 0)[Int]
          }
          assertEquals(resp, 0)
        }
    }.toFuture

  test("invoke: derived EchoService facade calls the matching server routes"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.invoke {
          val service = EchoService(ServerAppId)
          val echoed = retryUntilSuccess("derived echo through app channel") {
            service.echo("derived-js")
          }
          assertEquals(echoed, "derived-js")
          assertEquals(service.double(IncrRequest(21)), CounterState(42))
        }
    }.toFuture
