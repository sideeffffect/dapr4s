//> using target.platform "jvm"
package dapr4s.test.integration

import munit.FunSuite

/** JVM [[dapr4s.SecretsCapability]] integration suite: a one-line entry point over the shared [[SecretsSuiteDef]]
  * (registrations + scenarios) and [[SharedDaprItSuite]] (the canonical `secretstores.local.file` store). The JS twin
  * [[SecretsJsIntegrationTest]] runs the very same suite definition.
  */
@scala.caps.assumeSafe
class SecretsItTest extends FunSuite, SharedDaprItSuite, SecretsSuiteDef
