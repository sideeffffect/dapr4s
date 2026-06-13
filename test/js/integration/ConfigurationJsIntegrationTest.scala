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

/** Scala.js (Wasm+JSPI) [[ConfigurationCapability]] integration suite: a thin shell over the shared
  * [[ConfigurationScenarios]], run against the canonical `configuration.redis` store via the live sidecar. The JVM twin
  * [[ConfigurationItTest]] runs the same scenarios.
  *
  * Configuration is gRPC-only in the JS SDK, so this suite also exercises the lazily created gRPC-protocol client end
  * to end.
  */
@scala.caps.assumeSafe
class ConfigurationJsIntegrationTest extends FunSuite, ConfigurationScenarios:

  override def munitTimeout: Duration = 120.seconds

  private def run(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async(Dapr(clientConfig).run(body)).toFuture

  test("configuration: get returns the seeded items with values and versions")(run(getReturnsSeededItems))
  test("configuration: get for an unknown key returns no item for it")(run(getUnknownKeyReturnsNoItem))
