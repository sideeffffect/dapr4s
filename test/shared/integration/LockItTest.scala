package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.LockCapability]] integration suite — a SINGLE cross-platform file over the shared [[LockScenarios]] and the
  * per-platform [[SharedDaprItSuite]] bring-up (canonical `lock.redis` store).
  */
@scala.caps.assumeSafe
class LockItTest extends FunSuite, SharedDaprItSuite, LockScenarios:

  test("lock: tryLock on a free resource returns true")(withDapr(tryLockFreeReturnsTrue))
  test("lock: tryLock on a held resource returns false")(withDapr(tryLockHeldReturnsFalse))
  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound")(
    withDapr(unlockByOwnerThenLockNotFound),
  )
