package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.SecretsCapability]] integration suite — a SINGLE cross-platform file over the shared [[SecretsScenarios]]
  * and the per-platform [[SharedDaprItSuite]] bring-up (canonical `secretstores.local.file` store, seeded from
  * scripts/it/secrets.json).
  */
@scala.caps.assumeSafe
class SecretsItTest extends FunSuite, SharedDaprItSuite, SecretsScenarios:

  test("secrets: get for seeded keys returns Some")(withDapr(getSeededReturnsSome))
  test("secrets: getBulk contains the seeded keys")(withDapr(getBulkContainsSeeded))
  test("secrets: get for a missing key throws (local-file store answers 500)")(withDapr(getMissingKeyThrows))
  test("secrets: get from an unknown store throws")(withDapr(getFromUnknownStoreThrows))
