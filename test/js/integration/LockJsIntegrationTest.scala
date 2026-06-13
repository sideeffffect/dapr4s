//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** Scala.js (Wasm+JSPI) [[LockCapability]] integration suite: a thin shell over the shared [[LockScenarios]], run
  * against the canonical `lock.redis` store via the live sidecar. The JVM twin [[LockItTest]] runs the same scenarios.
  */
@scala.caps.assumeSafe
class LockJsIntegrationTest extends FunSuite, LockScenarios:

  override def munitTimeout: Duration = 120.seconds

  private def run(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async(Dapr(clientConfig).run(body)).toFuture

  test("lock: tryLock on a free resource returns true")(run(tryLockFreeReturnsTrue))
  test("lock: tryLock on a held resource returns false")(run(tryLockHeldReturnsFalse))
  test("lock: unlock by the owner returns Success, re-unlock returns LockNotFound")(run(unlockByOwnerThenLockNotFound))
