//> using target.platform "jvm"
package dapr4s.test.integration

/** JVM implementation of the cross-platform [[ItPolling]] helpers: a tick sleeps via `Thread.sleep`. Mixed into the
  * server-delivery integration suites that poll for sidecar-startup effects (placement table dissemination, job/actor
  * delivery), the twin of the JS [[JsItEnv]] which implements the same `sleep` via a JSPI timer.
  */
@scala.caps.assumeSafe
trait JvmItPolling extends ItPolling:
  override protected def sleep(ms: Int): Unit = Thread.sleep(ms.toLong)
