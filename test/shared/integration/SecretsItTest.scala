package dapr4s.test.integration

import munit.FunSuite

/** [[dapr4s.SecretsCapability]] integration suite — a SINGLE cross-platform entry point over the shared
  * [[SecretsSuiteDef]] and the per-platform [[SharedDaprItSuite]] bring-up (canonical `secretstores.local.file` store,
  * seeded from scripts/it/secrets.json).
  */
@scala.caps.assumeSafe
class SecretsItTest extends FunSuite, SharedDaprItSuite, SecretsSuiteDef
