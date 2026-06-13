//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM [[dapr4s.ConfigurationCapability]] integration suite: a thin shell over the shared [[ConfigurationScenarios]]
  * and [[SharedDaprItSuite]] (the canonical `configuration.redis` store). The JS twin
  * [[ConfigurationJsIntegrationTest]] runs the very same scenarios. Replaces the former
  * ConfigurationCapabilityServerTest.
  */
@scala.caps.assumeSafe
class ConfigurationItTest extends FunSuite, SharedDaprItSuite, ConfigurationScenarios:

  test("configuration: get returns the seeded items with values and versions")(withDapr(getReturnsSeededItems))
  test("configuration: get for an unknown key returns no item for it")(withDapr(getUnknownKeyReturnsNoItem))
