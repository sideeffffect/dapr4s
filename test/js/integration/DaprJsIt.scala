//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.internal.JsAwait
import dapr4s.test.integration.apps.{InventoryServiceApp, OrderServiceApp}
import java.net.URI
import scala.scalajs.js
import scala.util.control.NonFatal
import unsafeExceptions.canThrowAny
import dapr4styped.daprTestcontainerNode.distDaprContainerMod.{DaprContainer, StartedDaprContainer}
import dapr4styped.testcontainers.buildGenericContainerGenericContainerMod.GenericContainer
import dapr4styped.testcontainers.buildNetworkNetworkMod.{Network, StartedNetwork}
import dapr4styped.testcontainers.buildTestContainerMod.StartedTestContainer

/** Shared bring-up helpers for the Scala.js (Wasm+JSPI) integration fixtures, which drive a real Dapr sidecar from
  * inside the test runtime via `@dapr/testcontainer-node` — the exact twin of how the JVM suites use
  * `io.dapr:testcontainers-dapr`. The two cross-platform suite fixtures that build on these helpers,
  * [[SharedDaprItSuite]] (direct-call) and [[ServerDaprItSuite]] (server-delivery, via [[ServerDaprItEnv]]), live in
  * their own files alongside their JVM twins.
  *
  * `@dapr/testcontainer-node` defaults to Dapr 1.15.10; dapr4s targets 1.17.0, so the daprd image is passed to the
  * constructor and the placement/scheduler images are overridden to match. The library auto-manages the placement +
  * scheduler containers on the network we provide (it `assert`s a network is set), so — unlike the retired shell
  * harness — we do not start them by hand. Its wait strategy is `/v1.0/healthz/outbound`, which is ready WITHOUT the
  * app channel, so the server-delivery fixture can start the sidecar first and the in-process app server second (the
  * same sidecar-first order the JVM `ServerDaprItSuite` uses).
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

  // ---- service-suite environment (one sidecar + one DIRECT-HTTP service server) ----------------
  // The service suites (Order/Inventory/e2e) host OrderServiceApp ++ InventoryServiceApp behind a
  // DaprAppServer they poke DIRECTLY over HTTP (invoke routes + a synthetic CloudEvent POST to the
  // subscription route), exactly like the JVM `ServiceHarness`. The sidecar here has NO app channel
  // (plain `daprContainer`, no withAppPort/withAppChannelAddress), so a handler's fire-and-forget
  // publish is never redelivered — the test's direct POST is the only delivery (no double-count, no
  // poll). The stack is created lazily on the first service test and kept for the run (reaped at exit),
  // like ServerDaprItEnv.sidecar.
  private var serviceClientCfg: DaprConfig | Null = null
  private var serviceServerPort: Int = -1

  def serviceStack(): (DaprConfig, Int) =
    val existing = serviceClientCfg
    if existing != null then (existing, serviceServerPort)
    else
      val (net, _) = startNetworkAndRedis()
      val sd = JsAwait.await(daprContainer(net, "js-it-service").start())
      val sidecar = sidecarOf(sd)
      val port = nextAppPort()
      // The service server reaches the sidecar for state/lock/publish; the sidecar never reaches it
      // back (no app channel), so it is not exposed to Docker. serve suspends forever → fire-and-forget.
      Dapr(DaprConfig(sidecar = sidecar, appServer = AppServerConfig(port = DaprPort(port))))
        .serveAsync(OrderServiceApp() ++ InventoryServiceApp())
        .asInstanceOf[js.Dynamic]
        .applyDynamic("catch")(
          ((e: js.Any) => {
            js.Dynamic.global.console.error(s"dapr4s js-it: service server failed: $e")
            ()
          }): js.Function1[js.Any, Unit],
        ): Unit
      // GET /dapr/config answers 200 on the express server even with no actors — a readiness probe
      // that needs no app channel.
      awaitHttpOk("service server up", s"http://localhost:$port/dapr/config")
      val cfg = DaprConfig(sidecar = sidecar)
      serviceClientCfg = cfg
      serviceServerPort = port
      (cfg, port)

  /** POST `body` to `url` and return `(status, responseText)` — the JS analogue of the JVM `httpPostWithCode`. */
  def httpPostWithCode(url: String, body: String): (Int, String) =
    val resp = JsAwait.await(
      js.Dynamic.global
        .fetch(
          url,
          js.Dynamic.literal(
            method = "POST",
            headers = js.Dynamic.literal("Content-Type" -> "application/json"),
            body = body,
          ),
        )
        .asInstanceOf[js.Promise[js.Dynamic]],
    )
    val status = resp.status.asInstanceOf[Int]
    val text = JsAwait.await(resp.text().asInstanceOf[js.Promise[String]])
    (status, text)

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
