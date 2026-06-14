//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Scala.js (Wasm+JSPI) [[ConfigurationCapability]] integration suite: a thin shell over the shared
  * [[ConfigurationScenarios]], run against the canonical `configuration.redis` store via a sidecar started in-process
  * by [[SharedDaprJsItSuite]] (the JS twin of the JVM `SharedDaprItSuite`). The JVM twin [[ConfigurationItTest]] runs
  * the same scenarios.
  *
  * Configuration is gRPC-only in the JS SDK, so this suite also exercises the lazily created gRPC-protocol client end
  * to end.
  */
@scala.caps.assumeSafe
class ConfigurationJsIntegrationTest extends FunSuite, ConfigurationScenarios, SharedDaprJsItSuite:

  test("configuration: get returns the seeded items with values and versions")(withDapr(getReturnsSeededItems))
  test("configuration: get for an unknown key returns no item for it")(withDapr(getUnknownKeyReturnsNoItem))
