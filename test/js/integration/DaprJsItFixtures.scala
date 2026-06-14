//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.internal.JsAwait
import java.net.URI
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import scala.util.control.NonFatal
import unsafeExceptions.canThrowAny
import dapr4styped.daprTestcontainerNode.distDaprContainerMod.{DaprContainer, StartedDaprContainer}
import dapr4styped.testcontainers.buildGenericContainerGenericContainerMod.GenericContainer
import dapr4styped.testcontainers.buildNetworkNetworkMod.{Network, StartedNetwork}
import dapr4styped.testcontainers.buildTestContainerMod.StartedTestContainer

/** Shared constants + helpers for the Scala.js (Wasm+JSPI) integration fixtures, which drive a real Dapr sidecar from
  * inside the test runtime via `@dapr/testcontainer-node` — the exact twin of how the JVM suites use
  * `io.dapr:testcontainers-dapr`.
  *
  * `@dapr/testcontainer-node` defaults to Dapr 1.15.10; dapr4s targets 1.17.0, so the daprd image is passed to the
  * constructor and the placement/scheduler images are overridden to match. The library auto-manages the placement +
  * scheduler containers on the network we provide (it `assert`s a network is set), so — unlike the retired shell
  * harness — we do not start them by hand. Its wait strategy is `/v1.0/healthz/outbound`, which is ready WITHOUT the
  * app channel, so the server-delivery fixture can start the sidecar first and the in-process app server second (the
  * JVM `WorkflowCapabilityServerTest` two-phase order).
  *
  * ==Per-suite lifecycle on a single-threaded runtime==
  * The JVM `TestContainersForAll` stops a suite's containers + Docker network in `afterAll` before the next suite
  * starts, so only one suite's stack is alive at a time. munit's `afterAll` on Scala.js is synchronous and cannot await
  * the async container stop, and a network left attached to stopped-but-not-removed containers leaks — nine leaked
  * networks exhaust Docker's address pools. So instead each suite, at the START of its (js.async) environment setup,
  * AWAITS the teardown of the PREVIOUS suite's stack ([[rotateEnv]]) before creating its own — keeping at most one
  * stack alive, exactly like the JVM, just rotated forward instead of cleaned up backward. The final suite's stack is
  * reaped by the testcontainers Reaper at process exit.
  */
@scala.caps.assumeSafe
object DaprJsIt:
  val DaprdImage = "daprio/daprd:1.17.0"
  val PlacementImage = "daprio/placement:1.17.0"
  val SchedulerImage = "daprio/scheduler:1.17.0"
  val RedisImage = "redis:7-alpine"

  /** Per-suite app-server ports. The in-process `serve` suspends forever (no clean stop on JS), so each server-delivery
    * suite binds its own port — sequential munit suites never collide, and the lingering listeners are harmless until
    * the process exits.
    */
  private var portCounter: Int = 8400
  def nextAppPort(): Int =
    portCounter += 1
    portCounter

  // The previous suite's teardown thunk; awaited at the start of the next suite's setup.
  private var teardownPrevious: js.UndefOr[js.Function0[js.Promise[js.Any]]] = js.undefined

  /** Await teardown of the previously-started suite stack (if any), then remember this one's. Call from inside the
    * js.async env setup, BEFORE creating new containers, so at most one stack is alive at a time.
    */
  def rotateEnv(teardown: () => js.Promise[js.Any]): Unit =
    teardownPrevious.toOption.foreach(td => JsAwait.await(td()))
    teardownPrevious = (teardown: js.Function0[js.Promise[js.Any]])

  /** Ordered teardown: stop the daprd container (which stops its managed placement + scheduler), then redis, then
    * remove the now-detached network.
    */
  def teardownChain(
      sd: StartedDaprContainer,
      redis: StartedTestContainer,
      net: StartedNetwork,
  ): () => js.Promise[js.Any] =
    () =>
      js.async {
        stopAwait(sd.asInstanceOf[js.Dynamic])
        stopAwait(redis.asInstanceOf[js.Dynamic])
        stopAwait(net.asInstanceOf[js.Dynamic])
      }.asInstanceOf[js.Promise[js.Any]]

  private def stopAwait(c: js.Dynamic): Unit =
    try JsAwait.await(c.stop().asInstanceOf[js.Promise[js.Any]])
    catch case NonFatal(_) => ()

  /** Start a fresh Docker network + a redis (alias `redis`) on it, seed the config items, and return both. Shared by
    * both fixtures.
    */
  def startNetworkAndRedis(): (StartedNetwork, StartedTestContainer) =
    val net = JsAwait.await(new Network().start())
    val redis = new GenericContainer(RedisImage)
      .withExposedPorts(6379)
      .withNetwork(net)
      .withNetworkAliases(ItNames.RedisAlias)
    val startedRedis = JsAwait.await(redis.start())
    // Seed configuration items before daprd reads them (redis config store splits "value||version").
    JsAwait.await(startedRedis.exec(JsItComponents.SeedConfigArgv))
    (net, startedRedis)

  /** Build a canonical all-components daprd container on `net` (placement/scheduler pinned to 1.17.0), without an app
    * port.
    */
  def daprContainer(net: StartedNetwork, appName: String): DaprContainer =
    val dc = new DaprContainer(DaprdImage)
      .withNetwork(net)
      .withAppName(appName)
      .withPlacementImage(PlacementImage)
      .withSchedulerImage(SchedulerImage)
    JsItComponents.configure(dc)

  /** The sidecar HTTP + gRPC endpoints of a started daprd container, built from the mapped host + ports — the JS twin
    * of the JVM `DaprTestContainer.{httpEndpoint,grpcEndpoint}`. NOTE: we do NOT use
    * `StartedDaprContainer.getGrpcEndpoint()`, which is broken upstream (it returns `":<port>"` with no scheme or
    * host); `getHttpEndpoint()` is fine but we build both uniformly.
    */
  def sidecarOf(sd: StartedDaprContainer): SidecarConfig =
    val host = sd.asInstanceOf[StartedTestContainer].getHost()
    SidecarConfig(
      httpEndpoint = URI.create(s"http://$host:${sd.getHttpPort().toInt}"),
      grpcEndpoint = URI.create(s"http://$host:${sd.getGrpcPort().toInt}"),
    )

  /** Poll `fetch(url)` until it answers with a 2xx/3xx status, or the deadline passes. */
  def awaitHttpOk(label: String, url: String, timeoutMs: Int = 90000): Unit =
    JsItEnv.eventually(label, timeoutMs = timeoutMs, intervalMs = 500) {
      val status =
        try JsAwait.await(js.Dynamic.global.fetch(url).asInstanceOf[js.Promise[js.Dynamic]]).status.asInstanceOf[Int]
        catch case NonFatal(_) => -1
      if status >= 200 && status < 400 then Some(()) else None
    }

  // ---- shared server-delivery environment (one sidecar + one in-process union server) ----------
  // The JVM starts/stops a per-suite app server thread in afterAll; on JS `serve` suspends forever
  // with no clean stop, so the four server-delivery suites share ONE sidecar + ONE union server for
  // the whole run (the retired shell harness's topology, now testcontainers-managed). Created lazily
  // on the first server-delivery test, never torn down (the Reaper reaps it at process exit).
  private var sharedServerCfg: DaprConfig | Null = null

  def sharedServerConfig(): DaprConfig =
    val existing = sharedServerCfg
    if existing != null then existing
    else
      // Tolerate the testcontainers forwarder's transient ECONNREFUSED while daprd probes the app
      // channel before the in-process server binds (see installForwarderErrorGuard).
      installForwarderErrorGuard()
      val appPort = nextAppPort()
      // Make the host-side app server reachable from inside the daprd container.
      JsAwait.await(TestContainersStatics.exposeHostPorts(appPort))
      val (net, _) = startNetworkAndRedis()
      val dc = daprContainer(net, JsItEnv.ServerAppId.value)
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
      val sidecar = sidecarOf(sd)
      // Start the in-process union server pointed at the sidecar (workflow runtime → mapped gRPC,
      // actor state → mapped HTTP). serve suspends forever, so fire-and-forget; attach a catch so a
      // startup failure (bind / validation) does not become an unhandled rejection.
      Dapr(DaprConfig(sidecar = sidecar, appServer = AppServerConfig(port = DaprPort(appPort))))
        .serveAsync(jsItUnionApp)
        .asInstanceOf[js.Dynamic]
        .applyDynamic("catch")(
          ((e: js.Any) => {
            js.Dynamic.global.console.error(s"dapr4s js-it: in-process server failed: $e")
            ()
          }): js.Function1[js.Any, Unit],
        ): Unit
      // Memoize BEFORE the readiness wait so a slow/failed wait never re-binds the port on retry.
      val cfg = DaprConfig(sidecar = sidecar)
      sharedServerCfg = cfg
      // Wait until daprd has connected the app channel (subscriptions/actors registered) before tests run.
      awaitHttpOk("daprd healthz (app channel up)", s"${sidecar.httpEndpoint}/v1.0/healthz")
      cfg

  private var forwarderGuardInstalled = false

  /** Install (once) a process-level guard that swallows the transient `ECONNREFUSED`/`ECONNRESET` the testcontainers
    * host-port forwarder raises as an UNHANDLED socket `error` event while daprd probes the app channel before the
    * in-process server has bound its port. The JVM testcontainers forwarder tolerates this internally; the Node
    * ssh2-based one lets it bubble up and crash the process. daprd retries the probe, so the connection succeeds once
    * the server is listening — the refused attempts in between are benign. Any other uncaught error keeps the default
    * fatal behaviour (test bodies surface their own failures through the awaited promise chain, not here).
    */
  def installForwarderErrorGuard(): Unit =
    if !forwarderGuardInstalled then
      forwarderGuardInstalled = true
      js.Dynamic.global.process.on(
        "uncaughtException",
        ((e: js.Dynamic) => {
          val code = e.selectDynamic("code").asInstanceOf[js.UndefOr[String]].toOption.getOrElse("")
          if code == "ECONNREFUSED" || code == "ECONNRESET" || code == "EPIPE" then
            js.Dynamic.global.console.warn(s"dapr4s js-it: ignoring transient forwarder error ($code)"): Unit
          else
            js.Dynamic.global.console.error(s"dapr4s js-it: fatal uncaught exception: $e"): Unit
            js.Dynamic.global.process.exit(1): Unit
        }): js.Function1[js.Dynamic, Unit],
      ): Unit

/** Direct-call fixture (no app server) — the JS twin of `SharedDaprItSuite`. Mix into the State / Configuration /
  * Crypto / Lock / Secrets suites; they call the shared scenario traits against the canonical components via the
  * started sidecar. The sidecar (with placement/scheduler) is started once per suite, lazily, on the first test body
  * (which runs inside `js.async`, the only place the orphan-await container startup can suspend).
  */
@scala.caps.assumeSafe
trait SharedDaprJsItSuite extends FunSuite, DaprItFixture:
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

/** Server-delivery fixture — the JS twin of `RedisFixture` + the two-phase host-server setup the JVM actor/workflow
  * suites use. The Actor/PubSub/Invoke/Workflow suites mix this in; they all talk to the ONE shared sidecar +
  * in-process union server [[DaprJsIt.sharedServerConfig]] starts (see [[jsItUnionApp]] for why server-delivery is
  * shared rather than per-suite on JS), reached via `host.testcontainers.internal` exactly like
  * `ActorCapabilityServerTest` / `WorkflowCapabilityServerTest`.
  */
@scala.caps.assumeSafe
trait ServerDaprJsItSuite extends FunSuite, DaprItFixture:
  self: FunSuite =>

  override def munitTimeout: Duration = 120.seconds

  override def withDapr(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async {
      val cfg = DaprJsIt.sharedServerConfig()
      Dapr(cfg).run(body)
    }.toFuture
