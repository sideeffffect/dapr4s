package dapr4s.test.integration

import scala.util.control.NonFatal
import unsafeExceptions.canThrowAny

/** Cross-platform poll/retry helpers shared by the JVM and JS integration suites. The deadline loop is identical on
  * both platforms; only how a tick "sleeps" differs ([[sleep]] is `Thread.sleep` on the JVM, a JSPI timer await on
  * Scala.js), so it is the single abstract member.
  *
  * Mixed into the JS [[JsItEnv]] (an object) and the JVM [[JvmItPolling]] (mixed into the server-delivery suites). Both
  * sidecar-startup races they paper over — actor placement table dissemination and workflow runtime registration —
  * surface as 500s until ready.
  *
  * WHY @assumeSafe: the by-name `probe`/`body` are pure function types under `pureFunctions`, but callers pass closures
  * that capture Dapr capabilities from the enclosing `Dapr.run` scope (the standard test-side erasure the suites rely
  * on). Safe because the closures never outlive the `run` block that owns the capabilities.
  */
@scala.caps.assumeSafe
trait ItPolling:

  /** Suspend the current execution for ~`ms` milliseconds: `Thread.sleep` on the JVM, a JSPI timer await on Scala.js.
    */
  protected def sleep(ms: Int): Unit

  /** Poll `probe` until it returns `Some`, sleeping `intervalMs` between attempts; fail the test after `timeoutMs`. */
  def eventually[T](label: String, timeoutMs: Int = 30000, intervalMs: Int = 250)(probe: => Option[T]): T =
    val deadline = System.currentTimeMillis() + timeoutMs
    var result: Option[T] = probe
    while result.isEmpty && System.currentTimeMillis() < deadline do
      sleep(intervalMs)
      result = probe
    result.getOrElse(throw new AssertionError(s"eventually($label) timed out after ${timeoutMs}ms"))

  /** Retry `body` until it stops throwing (returning its value), for sidecar-startup races: actor placement table
    * dissemination and workflow runtime registration both surface as 500s until ready. Rethrows the last failure after
    * `timeoutMs`.
    */
  def retryUntilSuccess[T](label: String, timeoutMs: Int = 60000, intervalMs: Int = 500)(body: => T): T =
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable | Null = null
    while System.currentTimeMillis() < deadline do
      try return body
      catch
        case NonFatal(e) =>
          last = e
          sleep(intervalMs)
    val l = last
    throw new AssertionError(
      s"retryUntilSuccess($label) still failing after ${timeoutMs}ms: ${
          if l == null then "no attempt ran" else l.toString
        }",
    )
