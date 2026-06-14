package dapr4s.test.integration

import munit.FunSuite

/** [[dapr4s.ConfigurationCapability]] integration suite — a SINGLE cross-platform entry point over the shared
  * [[ConfigurationSuiteDef]] and the per-platform [[SharedDaprItSuite]] bring-up (canonical `configuration.redis`
  * store). Configuration is gRPC-only in the JS SDK, so on Scala.js this also exercises the lazily created
  * gRPC-protocol client end to end.
  */
@scala.caps.assumeSafe
class ConfigurationItTest extends FunSuite, SharedDaprItSuite, ConfigurationSuiteDef
