//> using target.platform "jvm"
package dapr4s.test.integration

import munit.FunSuite

/** JVM [[dapr4s.LockCapability]] integration suite: a one-line entry point over the shared [[LockSuiteDef]]
  * (registrations + scenarios) and [[SharedDaprItSuite]] (the canonical `lock.redis` store). The JS twin
  * [[LockJsIntegrationTest]] runs the very same suite definition.
  */
@scala.caps.assumeSafe
class LockItTest extends FunSuite, SharedDaprItSuite, LockSuiteDef
