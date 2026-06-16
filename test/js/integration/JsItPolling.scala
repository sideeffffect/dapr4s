//> using target.platform "scala-js"
package dapr4s.test.integration

import scala.scalajs.js

/** Scala.js implementation of the cross-platform [[ItPolling]] helpers: a tick "sleeps" by suspending the Wasm stack on
  * a timer promise, routed through [[dapr4s.internal.JsAwait]] (the one sanctioned home of orphan `js.await`). The twin
  * of the JVM [[JvmItPolling]] which implements the same `sleep` via `Thread.sleep`.
  *
  * Mixed into [[JsItEnv]] (so the JS suites keep `import JsItEnv.*`) and into [[ServerDaprItSuite]] /
  * `SharedDaprItSuite` so the shared suite classes inherit `eventually` / `retryUntilSuccess` directly from the
  * fixture.
  */
@scala.caps.assumeSafe
trait JsItPolling extends ItPolling:
  /** Suspend the current Wasm stack for `ms` milliseconds (the JS analogue of `Thread.sleep`). */
  override protected def sleep(ms: Int): Unit =
    dapr4s.internal.JsAwait.await(new js.Promise[Unit]((resolve, _) => {
      js.timers.setTimeout(ms.toDouble) { resolve(()); () }: Unit
      ()
    }))
