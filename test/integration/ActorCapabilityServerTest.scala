package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.internal.{DaprAppServer, HttpActorContext}
import dapr.safe.test.unit.DaprServerTestBase
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Tests for Counter actor dispatch via [[DaprAppServer]] HTTP, with actor state stored in a real Dapr sidecar.
  *
  * Setup is two-phase so the sidecar can discover actor types:
  *
  *   1. The app server starts first on a pre-allocated port, exposed to Docker via
  *      `org.testcontainers.Testcontainers.exposeHostPorts`.
  *   2. The Dapr sidecar (Testcontainers) starts with `withAppPort` + `withAppChannelAddress`, which causes it to call
  *      the app's `/dapr/config` endpoint and register the `Counter` actor type with the placement service.
  *   3. After the sidecar container is up, `sidecarPortRef` is updated to the actual mapped port; all subsequent
  *      [[HttpActorContext]] instances created by the actor dispatch path will reach the real sidecar.
  *
  * Two test tracks:
  *
  *   - **State-persistence tests** call the sidecar's actor invocation API
  *     (`POST http://sidecar/v1.0/actors/Counter/{id}/method/{name}`). The sidecar routes to the app, activates the
  *     actor instance, and permits state reads/writes on its behalf. This is the production call path.
  *   - **Routing tests** (404 cases, `/dapr/config`, DELETE) call the [[DaprAppServer]] HTTP endpoints directly to
  *     verify that the server's dispatch logic is correct, independent of the sidecar.
  *
  * Callback tests (reminder, timer) register a real short-duration reminder/timer through the sidecar and poll until
  * the sidecar fires it back to the app. This exercises the full sidecar ↔ app callback loop.
  *
  * Actor IDs are unique per test to prevent state leakage across tests that share the same sidecar container.
  */
@scala.caps.assumeSafe
class ActorCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  // Holds the sidecar's mapped HTTP port; updated after the container starts.
  // HttpActorContext reads this at creation time (per request), not at server startup.
  private val sidecarPortRef = new AtomicInteger(0)

  private var appServerThread: Thread | Null = null

  override def afterAll(): Unit =
    super.afterAll()
    val t = appServerThread
    if t != null then
      t.interrupt()
      t.join(2000)

  override def startContainers(): DaprTestContainer =
    // Make the host-side app server reachable from inside Docker containers.
    org.testcontainers.Testcontainers.exposeHostPorts(appPort)

    // Start the app server BEFORE the sidecar so the sidecar can call /dapr/config
    // and register the Counter actor type with the placement service.
    // sidecarPortRef is still 0 here; actor state calls will fail until it is updated below.
    val server = DaprAppServer(
      CounterActorHandlers.daprApp,
      mkActorCtx = (actorType, actorId, _) =>
        new HttpActorContext(actorType, actorId, sidecarPortRef.get()).asInstanceOf[ActorContext],
    )
    appServerThread = Thread.ofVirtual().start(() => server.startAndBlock(appPort))
    waitForPort(appPort, 5000)

    // Use Network.SHARED so the sidecar is on the same network as the Socat relay created by
    // exposeHostPorts above.  DaprContainer.configure() would otherwise create a NEW isolated
    // network; host.testcontainers.internal inside that network points to a different gateway
    // than the Socat relay — making the app server unreachable from the sidecar.
    val c = DaprTestContainer(
      DaprContainer("daprio/daprd:1.17.0")
        .withNetwork(org.testcontainers.containers.Network.SHARED)
        .withAppName("actor-server-test")
        .withAppPort(appPort)
        .withAppChannelAddress("host.testcontainers.internal")
        .withComponent(
          Component("statestore", "state.in-memory", "v1", java.util.Map.of("actorStateStore", "true")),
        ),
    )
    c.start()

    // Sidecar is now running.  Point HttpActorContext at the actual sidecar port so that
    // actor state reads/writes go to the real Dapr state store.
    val sidecarPort = java.net.URI.create(c.httpEndpoint).getPort
    sidecarPortRef.set(sidecarPort)

    // Wait for actor type to be registered with the placement service.
    // The sidecar health check passes before placement registration completes —
    // actor state API returns 500 until the sidecar has exchanged tables with the
    // placement service.  Poll until we get 204 (no state) instead of 500.
    waitForActorRegistration(sidecarPort, "Counter", "probe")

    c

  private def uniqueActorId() = s"actor-${java.util.UUID.randomUUID()}"

  // Direct call to app server — for routing/structural tests that don't touch state.
  private def appActorUrl(actorId: String, method: String): String =
    s"http://localhost:$appPort/actors/Counter/$actorId/method/$method"

  // Call through sidecar — actor is activated, state reads/writes are authorised.
  private def sidecarActorUrl(actorId: String, method: String): String =
    s"http://localhost:${sidecarPortRef.get()}/v1.0/actors/Counter/$actorId/method/$method"

  // Poll until state count reaches `expected` or timeout.
  private def waitForCount(actorId: String, expected: Int, maxMs: Int = 10000): Unit =
    val deadline = System.currentTimeMillis() + maxMs
    while System.currentTimeMillis() < deadline do
      val resp = httpPost(sidecarActorUrl(actorId, "get"), "null")
      if JsonCodec.decodeOrThrow[CounterState](resp).count == expected then return
      Thread.sleep(200)
    throw RuntimeException(
      s"Actor $actorId count never reached $expected within ${maxMs}ms",
    )

  // ---- state persistence via real Dapr sidecar --------------------------------

  test("actor: increment from zero — state stored in real Dapr sidecar"):
    withContainers { _ =>
      val id   = uniqueActorId()
      val resp = httpPost(sidecarActorUrl(id, "increment"), """{"amount":5}""")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 5)
    }

  test("actor: state persists across calls — HttpActorContext uses real sidecar"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(sidecarActorUrl(id, "increment"), """{"amount":3}""")
      val resp = httpPost(sidecarActorUrl(id, "increment"), """{"amount":7}""")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 10)
    }

  test("actor: get returns 0 for actor with no prior state"):
    withContainers { _ =>
      val id   = uniqueActorId()
      val resp = httpPost(sidecarActorUrl(id, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: reset brings count to zero — state written to real sidecar"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(sidecarActorUrl(id, "increment"), """{"amount":100}""")
      val resp = httpPost(sidecarActorUrl(id, "reset"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: get after reset returns 0"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(sidecarActorUrl(id, "increment"), """{"amount":50}""")
      httpPost(sidecarActorUrl(id, "reset"), "null")
      val resp = httpPost(sidecarActorUrl(id, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: state isolation — different actor IDs use separate sidecar keys"):
    withContainers { _ =>
      val id1 = uniqueActorId()
      val id2 = uniqueActorId()
      httpPost(sidecarActorUrl(id1, "increment"), """{"amount":10}""")
      httpPost(sidecarActorUrl(id2, "increment"), """{"amount":20}""")
      val r1 = httpPost(sidecarActorUrl(id1, "get"), "null")
      val r2 = httpPost(sidecarActorUrl(id2, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](r1).count, 10)
      assertEquals(JsonCodec.decodeOrThrow[CounterState](r2).count, 20)
    }

  // ---- HTTP routing -----------------------------------------------------------

  test("actor: unknown actor type returns 404"):
    withContainers { _ =>
      val (code, _) = httpPostWithCode(
        s"http://localhost:$appPort/actors/Nonexistent/1/method/get",
        "null",
      )
      assertEquals(code, 404)
    }

  test("actor: unknown method returns 404"):
    withContainers { _ =>
      val id        = uniqueActorId()
      val (code, _) = httpPostWithCode(appActorUrl(id, "no-such"), "null")
      assertEquals(code, 404)
    }

  test("actor: /dapr/config lists Counter actor type"):
    withContainers { _ =>
      val resp = httpGet(s"http://localhost:$appPort/dapr/config")
      assert(resp.contains("Counter"), s"Expected Counter in config response: $resp")
    }

  test("actor: DELETE deactivation returns 200"):
    withContainers { _ =>
      val id        = uniqueActorId()
      val (code, _) = httpDeleteWithCode(s"http://localhost:$appPort/actors/Counter/$id")
      assertEquals(code, 200)
    }

  // ---- callback dispatch (real sidecar-fired reminder and timer) --------------

  test("actor: reminder fires and resets counter — real sidecar reminder loop"):
    withContainers { _ =>
      val id = uniqueActorId()
      // Increment state through sidecar so the write is authorised.
      httpPost(sidecarActorUrl(id, "increment"), """{"amount":77}""")
      // Register a 1-second reminder via the sidecar so the write is authorised.
      httpPost(sidecarActorUrl(id, "schedule-quick-reset"), "null")
      // Poll until the sidecar fires the reminder and the handler resets the counter.
      waitForCount(id, 0)
    }

  test("actor: timer fires and increments counter — real sidecar timer loop"):
    withContainers { _ =>
      val id = uniqueActorId()
      // Increment state through sidecar so the write is authorised.
      httpPost(sidecarActorUrl(id, "increment"), """{"amount":10}""")
      // Register a 500ms timer via the sidecar so the write is authorised.
      httpPost(sidecarActorUrl(id, "schedule-auto-increment"), "null")
      // Poll until the sidecar fires the timer and the handler increments the counter.
      waitForCount(id, 11)
    }

  // ---- structural test --------------------------------------------------------

  test("actor: DaprApp ++ merges actor definitions"):
    withContainers { _ =>
      val app1     = CounterActorHandlers.daprApp
      val app2     = DaprApp(actors = List(ActorDefinition(ActorType("Other")) { (_, _) => ActorRoutes() }))
      val combined = app1 ++ app2
      assertEquals(combined.actors.size, 2)
      assert(combined.actors.exists(_.actorType.value == "Counter"))
      assert(combined.actors.exists(_.actorType.value == "Other"))
    }

  // ---- setup helpers ----------------------------------------------------------

  /** Poll the sidecar's actor state API until it stops returning 5xx.
    *
    * The Dapr placement service table exchange happens asynchronously after the sidecar's health check passes.
    * Until placement is complete, actor state reads return 500.  This polls until 204 (no state for probe actor)
    * which confirms the actor type is registered and state API is usable.
    */
  private def waitForActorRegistration(
      sidecarPort: Int,
      actorType: String,
      probeActorId: String,
      maxMs: Int = 30000,
  ): Unit =
    val url      = s"http://localhost:$sidecarPort/v1.0/actors/$actorType/$probeActorId/state/probe"
    val deadline = System.currentTimeMillis() + maxMs
    var lastMsg = ""
    while System.currentTimeMillis() < deadline do
      try
        val conn = java.net.URI.create(url).toURL.nn.openConnection().asInstanceOf[java.net.HttpURLConnection]
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(1000)
        conn.setReadTimeout(2000)
        conn.connect()
        val code = conn.getResponseCode
        conn.disconnect()
        // Any proper HTTP response (even 500) means the actor state API is up.
        // "Unexpected end of file" (getResponseCode throws) means the sidecar is still
        // bootstrapping actor types — keep polling.
        lastMsg = s"status=$code"
        if code < 500 then return
        // 500 can mean "not registered yet" or "actor state store error" — keep polling
      catch
        case e: java.net.SocketTimeoutException =>
          lastMsg = "timeout"
        case e: Exception =>
          lastMsg = e.getMessage.nn
      Thread.sleep(200)
    throw RuntimeException(
      s"Actor type $actorType not ready after ${maxMs}ms — last=$lastMsg",
    )

  // ---- HTTP helpers -----------------------------------------------------------

  private def httpDeleteWithCode(url: String): (Int, String) =
    val conn = java.net.URI(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
    conn.setRequestMethod("DELETE")
    conn.connect()
    val code   = conn.getResponseCode
    val stream =
      val err = conn.getErrorStream
      if err != null then err
      else if code < 400 then conn.getInputStream
      else null
    val resp = if stream == null then "" else new String(stream.nn.readAllBytes().nn, "UTF-8")
    (code, resp)
