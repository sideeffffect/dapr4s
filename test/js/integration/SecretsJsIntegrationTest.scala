//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Scala.js (Wasm+JSPI) [[SecretsCapability]] integration suite: a thin shell over the shared [[SecretsScenarios]], run
  * against the canonical `secretstores.local.file` store (seeded from scripts/it/secrets.json, mounted into the sidecar
  * by [[SharedDaprJsItSuite]]) via the live sidecar. The JVM twin [[SecretsItTest]] runs the same scenarios.
  */
@scala.caps.assumeSafe
class SecretsJsIntegrationTest extends FunSuite, SecretsScenarios, SharedDaprJsItSuite:

  test("secrets: get for seeded keys returns Some")(withDapr(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(withDapr(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(withDapr(getMissingKeyThrows))
  test("secrets: get from an unknown store throws")(withDapr(getFromUnknownStoreThrows))
