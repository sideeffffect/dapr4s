//> using target.platform "scala-js"
package dapr4s.test.integration

import munit.FunSuite

/** Scala.js (Wasm+JSPI) [[SecretsCapability]] integration suite: a one-line entry point over the shared
  * [[SecretsSuiteDef]] (registrations + scenarios), run against the canonical `secretstores.local.file` store (seeded
  * from scripts/it/secrets.json, mounted into the sidecar by [[SharedDaprJsItSuite]]). The JVM twin [[SecretsItTest]]
  * runs the very same suite definition.
  */
@scala.caps.assumeSafe
class SecretsJsIntegrationTest extends FunSuite, SharedDaprJsItSuite, SecretsSuiteDef
