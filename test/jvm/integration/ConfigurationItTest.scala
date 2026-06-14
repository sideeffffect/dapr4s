//> using target.platform "jvm"
package dapr4s.test.integration

import munit.FunSuite

/** JVM [[dapr4s.ConfigurationCapability]] integration suite: a one-line entry point over the shared
  * [[ConfigurationSuiteDef]] (registrations + scenarios) and [[SharedDaprItSuite]] (the canonical `configuration.redis`
  * store). The JS twin [[ConfigurationJsIntegrationTest]] runs the very same suite definition.
  */
@scala.caps.assumeSafe
class ConfigurationItTest extends FunSuite, SharedDaprItSuite, ConfigurationSuiteDef
