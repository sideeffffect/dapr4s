//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM [[dapr4s.SecretsCapability]] integration suite: a thin shell over the shared [[SecretsScenarios]] and
  * [[SharedDaprItSuite]] (the canonical `secretstores.local.file` store). The JS twin [[SecretsJsIntegrationTest]] runs
  * the very same scenarios. Replaces the former SecretsCapabilityServerTest (local.env, server-routed) +
  * SecretsIntegrationTest.
  */
@scala.caps.assumeSafe
class SecretsItTest extends FunSuite, SharedDaprItSuite, SecretsScenarios:

  test("secrets: get for seeded keys returns Some")(withDapr(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(withDapr(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(withDapr(getMissingKeyThrows))
  test("secrets: get from an unknown store throws")(withDapr(getFromUnknownStoreThrows))
