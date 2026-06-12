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

/** Scala.js (Wasm+JSPI) [[SecretsCapability]] integration suite: a thin shell over the shared [[SecretsScenarios]], run
  * against the canonical `secretstores.local.file` store (seeded from scripts/it/secrets.json) via the live sidecar.
  * The JVM twin [[SecretsItTest]] runs the same scenarios.
  */
@scala.caps.assumeSafe
class SecretsJsIntegrationTest extends FunSuite, SecretsScenarios:

  override def munitTimeout: Duration = 120.seconds

  private def run(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async(Dapr(clientConfig).run(body)).toFuture

  test("secrets: get for seeded keys returns Some")(run(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(run(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(run(getMissingKeyThrows))
  test("secrets: get from an unknown store throws")(run(getFromUnknownStoreThrows))
