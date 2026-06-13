package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.Assertions
import scala.concurrent.duration.DurationInt
import unsafeExceptions.canThrowAny

/** Direct-call [[LockCapability]] scenarios shared by the JVM and JS integration suites, against the canonical
  * `lock.redis` store. Unique resource IDs per call keep the shared sidecar contention-free.
  */
trait LockScenarios:
  self: Assertions =>

  private def res() = LockResourceId(ItNames.fresh("res"))
  private def owner() = LockOwner(ItNames.fresh("owner"))

  def tryLockFreeReturnsTrue(using DaprCapability): Unit =
    DaprCapability.lock(ItNames.LockStore):
      assert(LockCapability.tryLock(res(), owner(), 30.seconds))

  def tryLockHeldReturnsFalse(using DaprCapability): Unit =
    DaprCapability.lock(ItNames.LockStore):
      val r = res()
      assert(LockCapability.tryLock(r, owner(), 30.seconds), "first tryLock should succeed")
      assert(!LockCapability.tryLock(r, owner(), 30.seconds), "second tryLock should be contended")

  def unlockByOwnerThenLockNotFound(using DaprCapability): Unit =
    DaprCapability.lock(ItNames.LockStore):
      val r = res()
      val o = owner()
      assert(LockCapability.tryLock(r, o, 30.seconds))
      assertEquals(LockCapability.unlock(r, o), UnlockStatus.Success)
      assertEquals(LockCapability.unlock(r, o), UnlockStatus.LockNotFound)
