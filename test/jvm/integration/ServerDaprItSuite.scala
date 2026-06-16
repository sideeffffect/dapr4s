//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.itUnionApp
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import unsafeExceptions.canThrowAny

/** Server-delivery fixture — the JVM implementation of the cross-platform `ServerDaprItSuite` (the JS twin lives in
  * test/js). Hosts the shared [[itUnionApp]] (invoke routes + pub/sub subscriptions + the `Counter` actor + workflows)
  * on a real [[dapr4s.internal.DaprAppServer]] the sidecar reaches back into, so the shared `ActorItTest` /
  * `PubSubItTest` / `WorkflowItTest` / `InvokeItTest` exercise the *client* capabilities against a live sidecar.
  *
  * ==Bring-up order (the JS twin's order)==
  * The sidecar starts FIRST: the workflow runtime connects outbound to the sidecar gRPC endpoint at server-start, so
  * that endpoint must exist before the app server. daprd's wait is therefore overridden to `/v1.0/healthz/outbound`
  * (ready WITHOUT an app channel — the default `/v1.0/healthz` needs the app, which is not up yet). The app server is
  * then started via `Dapr(config).serve` on a virtual thread (the JVM analogue of the JS `serveAsync`); `serve` wires
  * the actor `sidecarHttpEndpoint` and the workflow `grpcEndpoint` from `config.sidecar`. `withAppHealthCheckPath`
  * points daprd's app probe at `/dapr/config` (an existing 200 endpoint) so daprd waits for the server, then
  * establishes the channel and registers the subscriptions + actor types.
  *
  * Sidecar-startup races (placement table dissemination, workflow runtime registration) surface as 500s until ready;
  * the shared suites poll through them with [[retrying]] / `eventually` ([[JvmItPolling]]) in the test bodies.
  */
@scala.caps.assumeSafe
trait ServerDaprItSuite extends TestContainersForAll, RedisFixture, DaprServerTestBase, DaprItFixture, JvmItPolling:
  self: FunSuite =>

  override type Containers = DaprTestContainer

  protected def serverAppId: AppId = ItNames.ServerAppId
  // The sidecar app-health-check gates channel establishment, but the placement/runtime tables still
  // disseminate asynchronously, so the shared suites retry the first call.
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private var appServerThread: Option[Thread] = None

  override def afterAll(): Unit =
    super.afterAll()
    appServerThread.foreach { t => t.interrupt(); t.join(2000) }

  override def startContainers(): DaprTestContainer =
    // Make the host-side app server reachable from inside the daprd container (Network.SHARED so the
    // sidecar shares the Socat relay's network exposeHostPorts sets up; an isolated network would make
    // host.testcontainers.internal point at a different gateway than the relay).
    org.testcontainers.Testcontainers.exposeHostPorts(appPort)

    val res = startRedis(Network.SHARED)
    val dc = DaprContainer(DaprTestContainer.DefaultImage)
      .withNetwork(Network.SHARED)
      .withAppName(serverAppId.value)
      .withAppPort(appPort)
      .withAppChannelAddress("host.testcontainers.internal")
      // Wait for the app to be healthy before establishing the channel (the server starts AFTER the
      // sidecar), then register subscriptions + actor types. /dapr/config is an existing 200 endpoint.
      .withAppHealthCheckPath("/dapr/config")
      .withComponent(res.component("statestore"))
      .withComponent(res.component("pubsub"))
    val c = DaprTestContainer(dc)
    // Override the default /v1.0/healthz wait (which needs the app channel) with the outbound probe, so
    // c.start() returns before the app server exists — the sidecar-first order the workflow runtime needs.
    c.container.waitingFor(
      Wait.forHttp("/v1.0/healthz/outbound").forPort(3500).forStatusCode(204),
    )
    c.start()

    // Start the in-process union server pointed at the now-running sidecar (workflow runtime → gRPC,
    // actor state → HTTP, both derived from config.sidecar). `serve` blocks, so run it on a vthread.
    val serverDapr = Dapr(
      DaprConfig(
        sidecar = SidecarConfig(httpEndpoint = c.httpEndpoint, grpcEndpoint = c.grpcEndpoint),
        appServer = AppServerConfig(port = DaprPort(appPort)),
      ),
    )
    appServerThread = Some(Thread.ofVirtual().start(() => serverDapr.serve(itUnionApp)))
    waitForPort(appPort, 10000)
    c

  /** Run `body` against the started sidecar with a [[DaprCapability]] in scope — the JVM analogue of the JS suites'
    * `Dapr(clientConfig).run`.
    */
  override def withDapr(body: DaprCapability ?=> Unit): Unit =
    withContainers { c => Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint)(body) }
