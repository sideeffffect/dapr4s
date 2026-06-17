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

/** Shared, started-ONCE sidecar + in-process union server for ALL JS server-delivery suites — the JS twin of the JVM
  * [[ServerDaprItEnv]] singleton.
  *
  * The JVM starts/stops a per-suite app server thread in afterAll; on JS `serve` suspends forever with no clean stop,
  * so the four server-delivery suites share ONE sidecar + ONE union server for the whole run (the retired shell
  * harness's topology, now testcontainers-managed). Created lazily on the first server-delivery test, never torn down
  * (the Reaper reaps it at process exit). Suites stay isolated via per-call fresh ids ([[ItNames.fresh]]).
  *
  * The sidecar starts FIRST (the workflow runtime needs its gRPC endpoint at server start), reachable from inside the
  * daprd container via `host.testcontainers.internal`. `withAppHealthCheckPath("/dapr/config")` makes daprd wait for
  * the in-process app and then establish the channel (registering subscriptions + actor types); we finally poll
  * `/v1.0/healthz` until that channel is up — see [[itUnionApp]] for why server-delivery is shared rather than
  * per-suite on JS.
  */
@scala.caps.assumeSafe
object ServerDaprItEnv:

  private var started: SidecarConfig | Null = null

  /** The shared sidecar config, starting the stack on first call — the JS analogue of the JVM `ServerDaprItEnv.sidecar`
    * (same signature, so both platforms' `withDapr` read `Dapr(DaprConfig(sidecar = ServerDaprItEnv.sidecar()))`).
    */
  def sidecar(): SidecarConfig =
    val existing = started
    if existing != null then existing
    else
      // Tolerate the testcontainers forwarder's transient ECONNREFUSED while daprd probes the app
      // channel before the in-process server binds (see installForwarderErrorGuard).
      DaprJsIt.installForwarderErrorGuard()
      val appPort = DaprJsIt.nextAppPort()
      // Make the host-side app server reachable from inside the daprd container.
      JsAwait.await(TestContainersStatics.exposeHostPorts(appPort))
      val (net, _) = DaprJsIt.startNetworkAndRedis()
      val dc = DaprJsIt
        .daprContainer(net, JsItEnv.ServerAppId.value)
        .withAppPort(appPort.toDouble)
        .withAppChannelAddress("host.testcontainers.internal")
        // The in-process server can only start AFTER the sidecar (the workflow runtime needs the
        // sidecar gRPC endpoint), so it is not listening when daprd first establishes the app
        // channel — without health checks daprd backs off and never registers our subscriptions /
        // actor types (on-demand invoke still works, which is why only pub-sub/actor/workflow
        // failed). Enabling app health checks makes daprd wait for the app to be healthy and THEN
        // (re)establish the channel. We point the probe at `/dapr/config`, an existing 200 endpoint
        // the server already answers — no dedicated health route needed.
        .withAppHealthCheckPath("/dapr/config")
      val sd = JsAwait.await(dc.start())
      val sc = DaprJsIt.sidecarOf(sd)
      // Start the in-process union server pointed at the sidecar (workflow runtime → mapped gRPC,
      // actor state → mapped HTTP). serve suspends forever, so fire-and-forget; attach a catch so a
      // startup failure (bind / validation) does not become an unhandled rejection.
      Dapr(DaprConfig(sidecar = sc, appServer = AppServerConfig(port = DaprPort(appPort))))
        .serveAsync(itUnionApp)
        .asInstanceOf[js.Dynamic]
        .applyDynamic("catch")(
          ((e: js.Any) => {
            js.Dynamic.global.console.error(s"dapr4s js-it: in-process server failed: $e")
            ()
          }): js.Function1[js.Any, Unit],
        ): Unit
      // Memoize BEFORE the readiness wait so a slow/failed wait never re-binds the port on retry.
      started = sc
      // Wait until daprd has connected the app channel (subscriptions/actors registered) before tests run.
      DaprJsIt.awaitHttpOk("daprd healthz (app channel up)", s"${sc.httpEndpoint}/v1.0/healthz")
      sc

/** Server-delivery fixture — the JS implementation of the cross-platform `ServerDaprItSuite` (the JVM twin lives in
  * test/jvm). The shared `ActorItTest` / `PubSubItTest` / `WorkflowItTest` / `InvokeItTest` mix it in; they all talk to
  * the ONE shared sidecar + in-process union server [[ServerDaprItEnv]] starts, reached via
  * `host.testcontainers.internal`.
  *
  * Provides the cross-platform hooks the shared suites use: `withDapr` ([[DaprItFixture]]), `eventually` /
  * `retryUntilSuccess` ([[JsItPolling]]), and the [[serverAppId]] / [[retrying]] that `InvokeItTest` targets. On JS the
  * sidecar reports healthy slightly before the app channel finishes warming up, so `retrying` retries the first call.
  */
@scala.caps.assumeSafe
trait ServerDaprItSuite extends FunSuite, DaprItFixture, JsItPolling:
  self: FunSuite =>

  override def munitTimeout: Duration = 120.seconds

  protected def serverAppId: AppId = ItNames.ServerAppId
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)

  override def withDapr(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async {
      Dapr(DaprConfig(sidecar = ServerDaprItEnv.sidecar())).run(body)
    }.toFuture
