//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.internal.JsAwait
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny

/** Direct-call fixture (no app server) — the JS twin of the JVM `SharedDaprItSuite`. Mix into the State / Configuration
  * / Crypto / Lock / Secrets suites; they call the shared scenario traits against the canonical components via the
  * started sidecar. The sidecar (with placement/scheduler) is started once per suite, lazily, on the first test body
  * (which runs inside `js.async`, the only place the orphan-await container startup can suspend).
  */
@scala.caps.assumeSafe
trait SharedDaprItSuite extends FunSuite, DaprItFixture:
  self: FunSuite =>

  override def munitTimeout: Duration = 120.seconds

  protected def appName: String = "shared-it"

  private var clientCfg: DaprConfig | Null = null

  private def ensureEnv(): DaprConfig =
    val existing = clientCfg
    if existing != null then existing
    else
      val (net, redis) = DaprJsIt.startNetworkAndRedis()
      val sd = JsAwait.await(DaprJsIt.daprContainer(net, appName).start())
      DaprJsIt.rotateEnv(DaprJsIt.teardownChain(sd, redis, net))
      val cfg = DaprConfig(sidecar = DaprJsIt.sidecarOf(sd))
      clientCfg = cfg
      cfg

  /** Run `body` against the started sidecar — the JS analogue of the JVM `withDapr`, wrapped in the
    * `js.async{}.toFuture` boundary munit awaits.
    */
  override def withDapr(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async {
      val cfg = ensureEnv()
      Dapr(cfg).run(body)
    }.toFuture
