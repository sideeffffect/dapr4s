//> using target.platform "scala-js"
package dapr4s.test.integration

import munit.FunSuite

/** Scala.js (Wasm+JSPI) [[ConfigurationCapability]] integration suite: a one-line entry point over the shared
  * [[ConfigurationSuiteDef]] (registrations + scenarios), run against the canonical `configuration.redis` store via a
  * sidecar started in-process by [[SharedDaprJsItSuite]]. The JVM twin [[ConfigurationItTest]] runs the very same suite
  * definition.
  *
  * Configuration is gRPC-only in the JS SDK, so this suite also exercises the lazily created gRPC-protocol client end
  * to end.
  */
@scala.caps.assumeSafe
class ConfigurationJsIntegrationTest extends FunSuite, SharedDaprJsItSuite, ConfigurationSuiteDef
