package dapr4s.test.integration

import munit.FunSuite

/** [[dapr4s.StateCapability]] integration suite — a SINGLE cross-platform entry point. The registrations + scenarios
  * live in [[StateSuiteDef]]; the bring-up comes from [[SharedDaprItSuite]], a trait with one implementation per
  * platform under the same name (testcontainers-java on the JVM, `@dapr/testcontainer-node` on Scala.js). Each platform
  * build links its own `SharedDaprItSuite`, so this one file compiles and runs as the suite on both.
  *
  * Route dispatch is covered by the unit ServerRouteDerivationTest; this exercises the capability directly against a
  * real sidecar on the canonical `state.redis` component.
  */
@scala.caps.assumeSafe
class StateItTest extends FunSuite, SharedDaprItSuite, StateSuiteDef
