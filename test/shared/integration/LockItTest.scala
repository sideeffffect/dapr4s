package dapr4s.test.integration

import munit.FunSuite

/** [[dapr4s.LockCapability]] integration suite — a SINGLE cross-platform entry point over the shared [[LockSuiteDef]]
  * and the per-platform [[SharedDaprItSuite]] bring-up (canonical `lock.redis` store).
  */
@scala.caps.assumeSafe
class LockItTest extends FunSuite, SharedDaprItSuite, LockSuiteDef
