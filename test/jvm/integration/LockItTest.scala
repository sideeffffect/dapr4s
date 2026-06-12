//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM [[dapr4s.LockCapability]] integration suite: a thin shell over the shared [[LockScenarios]] and
  * [[SharedDaprItSuite]] (the canonical `lock.redis` store). The JS twin [[LockJsIntegrationTest]] runs the very same
  * scenarios. Replaces the former LockCapabilityServerTest (server-routed).
  */
@scala.caps.assumeSafe
class LockItTest extends FunSuite, SharedDaprItSuite, LockScenarios:

  test("lock: tryLock on a free resource returns true")(withDapr(tryLockFreeReturnsTrue))
  test("lock: tryLock on a held resource returns false")(withDapr(tryLockHeldReturnsFalse))
  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound")(
    withDapr(unlockByOwnerThenLockNotFound),
  )
