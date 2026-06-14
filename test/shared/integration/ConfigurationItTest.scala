package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.ConfigurationCapability]] integration suite — a SINGLE cross-platform file over the shared
  * [[ConfigurationScenarios]] and the per-platform [[SharedDaprItSuite]] bring-up (canonical `configuration.redis`
  * store). Configuration is gRPC-only in the JS SDK, so on Scala.js this also exercises the lazily created
  * gRPC-protocol client end to end.
  */
@scala.caps.assumeSafe
class ConfigurationItTest extends FunSuite, SharedDaprItSuite, ConfigurationScenarios:

  test("configuration: get returns the seeded items with values and versions")(withDapr(getReturnsSeededItems))
  test("configuration: get for an unknown key returns no item for it")(withDapr(getUnknownKeyReturnsNoItem))
