//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import scala.scalajs.js

/** Scala.js integration-suite constants + the JS implementation of the cross-platform [[ItPolling]] helpers.
  *
  * Bring-up lives in [[DaprJsIt]] / [[SharedDaprItSuite]] / [[ServerDaprJsItSuite]], which drive a real Dapr sidecar
  * from inside the test runtime via `@dapr/testcontainer-node` — the twin of the JVM testcontainers fixtures.
  *
  * ==Why a JS-specific [[sleep]]==
  * The poll/retry loops are shared (see [[ItPolling]]); only sleeping differs. On a single-threaded JS runtime
  * "sleeping" means suspending on a timer promise — routed through [[dapr4s.internal.JsAwait]], the one sanctioned home
  * of orphan `js.await` (AGENTS.md: never import `allowOrphanJSAwait` anywhere else). So [[eventually]] /
  * [[retryUntilSuccess]] only work on the Wasm+JSPI backend, like the capability calls around them.
  *
  * WHY @assumeSafe: see [[ItPolling]] — the by-name probe/body closures capture Dapr capabilities from the enclosing
  * `Dapr.run` scope.
  */
@scala.caps.assumeSafe
object JsItEnv extends ItPolling:

  /** The app id every server-delivery suite registers its routes under (what `InvokeScenarios` targets); the daprd
    * container's `--app-id` is set to this.
    */
  val ServerAppId: AppId = AppId("js-it-server")

  // Canonical component names, config/secret keys and the `fresh` id generator live once in the
  // cross-platform ItNames; re-export them so the JS suites keep a single `import JsItEnv.*`.
  export ItNames.*

  /** Suspend the current Wasm stack for `ms` milliseconds (the JS analogue of `Thread.sleep`). */
  override protected def sleep(ms: Int): Unit =
    dapr4s.internal.JsAwait.await(new js.Promise[Unit]((resolve, _) => {
      js.timers.setTimeout(ms.toDouble) { resolve(()); () }: Unit
      ()
    }))
