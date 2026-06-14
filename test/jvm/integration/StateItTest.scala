//> using target.platform "jvm"
package dapr4s.test.integration

import munit.FunSuite

/** JVM [[dapr4s.StateCapability]] integration suite: a one-line entry point over the shared [[StateSuiteDef]]
  * (registrations + scenarios) and [[SharedDaprItSuite]] (the testcontainers sidecar on the canonical `state.redis`
  * component). The JS twin [[StateJsIntegrationTest]] runs the very same suite definition.
  *
  * Route dispatch is covered by the unit ServerRouteDerivationTest; this exercises the capability directly against a
  * real sidecar, identically to JS.
  */
@scala.caps.assumeSafe
class StateItTest extends FunSuite, SharedDaprItSuite, StateSuiteDef
