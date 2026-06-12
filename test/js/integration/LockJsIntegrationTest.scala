//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[LockCapability]] against a real `lock.redis` component — the Scala.js twin of [[LockCapabilityServerTest]]. Unique
  * resource IDs per test keep the shared sidecar contention-free across runs.
  */
@scala.caps.assumeSafe
class LockJsIntegrationTest extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  private def uniqueResource() = LockResourceId(s"js-it-res-${uniqueId()}")
  private def uniqueOwner() = LockOwner(s"js-it-owner-${uniqueId()}")

  test("lock: tryLock on a free resource returns true"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.lock(LockStore) {
          assert(LockCapability.tryLock(uniqueResource(), uniqueOwner(), 30.seconds))
        }
    }.toFuture

  test("lock: tryLock on a held resource returns false"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.lock(LockStore) {
          val res = uniqueResource()
          assert(LockCapability.tryLock(res, uniqueOwner(), 30.seconds), "first tryLock should succeed")
          assert(!LockCapability.tryLock(res, uniqueOwner(), 30.seconds), "second tryLock should be contended")
        }
    }.toFuture

  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.lock(LockStore) {
          val res = uniqueResource()
          val owner = uniqueOwner()
          assert(LockCapability.tryLock(res, owner, 30.seconds))
          assertEquals(LockCapability.unlock(res, owner), UnlockStatus.Success)
          assertEquals(LockCapability.unlock(res, owner), UnlockStatus.LockNotFound)
        }
    }.toFuture
