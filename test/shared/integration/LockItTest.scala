package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import unsafeExceptions.canThrowAny

/** [[dapr4s.LockCapability]] integration suite — a SINGLE cross-platform file, run via the per-platform
  * [[SharedDaprItSuite]] bring-up against the canonical `lock.redis` store. Unique resource IDs per call keep the
  * shared sidecar contention-free.
  */
@scala.caps.assumeSafe
class LockItTest extends FunSuite, SharedDaprItSuite:

  test("lock: tryLock on a free resource returns true")(withDapr(tryLockFreeReturnsTrue))
  test("lock: tryLock on a held resource returns false")(withDapr(tryLockHeldReturnsFalse))
  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound")(
    withDapr(unlockByOwnerThenLockNotFound),
  )

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
