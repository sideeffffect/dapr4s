//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import com.dimafeng.testcontainers.GenericContainer
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import scala.concurrent.duration.{Duration, DurationInt}
import unsafeExceptions.canThrowAny

/** Shared, started-ONCE Redis + sidecar + in-process union server for ALL JVM server-delivery suites — the JVM twin of
  * the JS `ServerDaprItEnv.sidecar` singleton.
  *
  * ==Why a singleton, not per-suite Testcontainers==
  * [[itUnionApp]] hosts a workflow runtime (a `DurableTaskGrpcWorker` streaming to the sidecar scheduler). Standing one
  * up and tearing it down for EACH of the four server suites (Actor/PubSub/Invoke/Workflow) left worker threads
  * retrying torn-down sidecars and wedged the whole JVM test run. Hosting ONE sidecar + ONE server for the entire run —
  * started lazily on the first `withDapr`, reaped by the Testcontainers Ryuk at JVM exit, never stopped per-suite —
  * matches the JS topology and removes the repeated teardown. Suites stay isolated via per-call fresh ids
  * ([[ItNames.fresh]]).
  *
  * ==Bring-up order==
  * The sidecar starts FIRST (the workflow runtime needs its gRPC endpoint at server start), so daprd's wait is
  * overridden to `/v1.0/healthz/outbound` — ready WITHOUT an app channel, since the default `/v1.0/healthz` needs the
  * app, which is not up yet. Then `Dapr(config).serve(itUnionApp)` runs on a virtual thread (virtual threads don't
  * block JVM exit; `serve`'s own shutdown hook stops the server + closes the runtime at exit).
  * `withAppHealthCheckPath("/dapr/config")` makes daprd wait for the app and then establish the channel (registering
  * subscriptions + actor types); we finally poll `/v1.0/healthz` until that channel is up.
  */
@scala.caps.assumeSafe
object ServerDaprItEnv:

  private var started: SidecarConfig | Null = null

  /** The shared sidecar config, starting the stack on first call. Synchronized: munit may run suites in parallel. */
  def sidecar(): SidecarConfig = synchronized {
    started match
      case existing: SidecarConfig => existing
      case null                    =>
        val appPort =
          val s = java.net.ServerSocket(0)
          val p = s.getLocalPort
          s.close()
          p
        // Make the host-side app server reachable from inside the daprd container (Network.SHARED so the
        // sidecar shares the Socat relay's network exposeHostPorts sets up).
        org.testcontainers.Testcontainers.exposeHostPorts(appPort)

        val redis = GenericContainer(
          dockerImage = "redis:7-alpine",
          exposedPorts = Seq(6379),
          waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
        )
        redis.container.withNetwork(Network.SHARED)
        redis.container.withNetworkAliases(ItNames.RedisAlias)
        redis.start()
        val res = JvmItComponents.render()

        val dapr = DaprContainer(DaprTestContainer.DefaultImage)
          .withNetwork(Network.SHARED)
          .withAppName(ItNames.ServerAppId.value)
          .withAppPort(appPort)
          .withAppChannelAddress("host.testcontainers.internal")
          .withAppHealthCheckPath("/dapr/config")
          .withComponent(res.component("statestore"))
          .withComponent(res.component("pubsub"))
        // /v1.0/healthz/outbound is ready WITHOUT the app channel, so start() returns before the app
        // server exists — the sidecar-first order the workflow runtime needs.
        dapr.setWaitStrategy(Wait.forHttp("/v1.0/healthz/outbound").forPort(3500).forStatusCode(204))
        dapr.start()

        val sidecarCfg = SidecarConfig(
          httpEndpoint = java.net.URI.create(s"http://${dapr.getHost}:${dapr.getHttpPort}"),
          grpcEndpoint = java.net.URI.create(s"http://${dapr.getHost}:${dapr.getGrpcPort}"),
        )
        // Host the union app, pointed at the sidecar. `serve` blocks forever, so run it on a virtual
        // thread; it is never stopped explicitly (its own JVM-shutdown hook drains it at exit).
        Thread
          .ofVirtual()
          .start(() =>
            Dapr(
              DaprConfig(
                sidecar = sidecarCfg,
                appServer = AppServerConfig(port = DaprPort(appPort)),
              ),
            ).serve(itUnionApp),
          )
        waitForHealthz(sidecarCfg.httpEndpoint)
        started = sidecarCfg
        sidecarCfg
  }

  /** Poll the sidecar's full health endpoint until the app channel is up (204), so subscriptions + actor types are
    * registered before the first test runs.
    */
  private def waitForHealthz(http: java.net.URI, maxMs: Int = 60000): Unit =
    val url = s"$http/v1.0/healthz"
    val deadline = System.currentTimeMillis() + maxMs
    var ok = false
    while !ok && System.currentTimeMillis() < deadline do
      try
        val conn = java.net.URI.create(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(1000)
        conn.setReadTimeout(2000)
        conn.connect()
        val code = conn.getResponseCode
        conn.disconnect()
        if code == 204 then ok = true else Thread.sleep(250)
      catch case _: Exception => Thread.sleep(250)
    if !ok then throw RuntimeException(s"sidecar app channel not healthy within ${maxMs}ms")

/** Server-delivery fixture — the JVM implementation of the cross-platform `ServerDaprItSuite` (the JS twin lives in
  * test/js). The shared `ActorItTest` / `PubSubItTest` / `WorkflowItTest` / `InvokeItTest` mix it in; each runs its
  * bodies against the shared [[ServerDaprItEnv]] stack via a fresh client `Dapr.run`.
  *
  * Sidecar-startup races (placement-table dissemination, workflow-runtime registration) surface as 500s until ready;
  * the bodies poll through them with [[retrying]] / `eventually` ([[JvmItPolling]]).
  */
@scala.caps.assumeSafe
trait ServerDaprItSuite extends FunSuite, DaprItFixture, JvmItPolling:

  // The bodies use retryUntilSuccess (≤60s) and waitForCompletion(60s), which would trip munit's 30s default.
  override def munitTimeout: Duration = 120.seconds

  protected def serverAppId: AppId = ItNames.ServerAppId
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)

  override def withDapr(body: DaprCapability ?=> Unit): Unit =
    Dapr(DaprConfig(sidecar = ServerDaprItEnv.sidecar())).run(body)
