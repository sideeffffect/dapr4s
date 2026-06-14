//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Scala.js (Wasm+JSPI) [[LockCapability]] integration suite: a thin shell over the shared [[LockScenarios]], run
  * against the canonical `lock.redis` store via a sidecar started in-process by [[SharedDaprJsItSuite]]. The JVM twin
  * [[LockItTest]] runs the same scenarios.
  */
@scala.caps.assumeSafe
class LockJsIntegrationTest extends FunSuite, LockScenarios, SharedDaprJsItSuite:

  test("lock: tryLock on a free resource returns true")(withDapr(tryLockFreeReturnsTrue))
  test("lock: tryLock on a held resource returns false")(withDapr(tryLockHeldReturnsFalse))
  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound")(
    withDapr(unlockByOwnerThenLockNotFound),
  )
