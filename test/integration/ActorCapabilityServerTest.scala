package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.internal.DaprAppServer
import dapr.safe.test.unit.DaprServerTestBase
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny
import java.util.Collections

/** Tests for Counter actor dispatch via [[DaprAppServer]] HTTP with a real Dapr sidecar for state storage.
  *
  * The Dapr sidecar (via Testcontainers) provides a real `state.in-memory` store. The test posts directly to the
  * [[DaprAppServer]] HTTP server (simulating what the sidecar would send for actor invocations), so no app-port
  * registration with the sidecar is needed. The [[dapr.safe.internal.HttpActorContext]] inside the server calls the
  * real sidecar for every state read/write, giving genuine end-to-end coverage of actor state persistence.
  *
  * Actor IDs are unique per test to prevent state leakage across tests sharing the same sidecar container.
  */
@scala.caps.assumeSafe
class ActorCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private var appServerThread: Thread | Null = null

  override def afterAll(): Unit =
    super.afterAll()
    val t = appServerThread
    if t != null then
      t.interrupt()
      t.join(2000)

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer("daprio/daprd:1.17.0")
        .withAppName("actor-server-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap())),
    )
    c.start()

    // Start app server with the sidecar's actual HTTP port so HttpActorContext can reach it.
    val sidecarHttpPort = java.net.URI.create(c.httpEndpoint).getPort
    val server          = new DaprAppServer(CounterActorHandlers.daprApp)
    appServerThread = Thread.ofVirtual().start(() => server.startAndBlock(appPort, sidecarHttpPort))
    waitForPort(appPort, 5000)
    c

  private def uniqueActorId() = s"actor-${java.util.UUID.randomUUID()}"

  private def actorUrl(actorId: String, method: String): String =
    s"http://localhost:$appPort/actors/Counter/$actorId/method/$method"

  // ---- state persistence via real Dapr sidecar --------------------------------

  test("actor: increment from zero — state stored in real Dapr sidecar"):
    withContainers { _ =>
      val id   = uniqueActorId()
      val resp = httpPost(actorUrl(id, "increment"), """{"amount":5}""")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 5)
    }

  test("actor: state persists across calls — HttpActorContext uses real sidecar"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(actorUrl(id, "increment"), """{"amount":3}""")
      val resp = httpPost(actorUrl(id, "increment"), """{"amount":7}""")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 10)
    }

  test("actor: get returns 0 for actor with no prior state"):
    withContainers { _ =>
      val id   = uniqueActorId()
      val resp = httpPost(actorUrl(id, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: reset brings count to zero — state written to real sidecar"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(actorUrl(id, "increment"), """{"amount":100}""")
      val resp = httpPost(actorUrl(id, "reset"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: get after reset returns 0"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(actorUrl(id, "increment"), """{"amount":50}""")
      httpPost(actorUrl(id, "reset"), "null")
      val resp = httpPost(actorUrl(id, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: state isolation — different actor IDs use separate sidecar keys"):
    withContainers { _ =>
      val id1 = uniqueActorId()
      val id2 = uniqueActorId()
      httpPost(actorUrl(id1, "increment"), """{"amount":10}""")
      httpPost(actorUrl(id2, "increment"), """{"amount":20}""")
      val r1 = httpPost(actorUrl(id1, "get"), "null")
      val r2 = httpPost(actorUrl(id2, "get"), "null")
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
      val (code, _) = httpPostWithCode(actorUrl(id, "no-such"), "null")
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

  // ---- callback dispatch (simulates sidecar → app delivery) ------------------

  test("actor: reminder callback resets counter — state read/written via real sidecar"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(actorUrl(id, "increment"), """{"amount":77}""")
      val dataB64     = java.util.Base64.getEncoder.nn.encodeToString("\"reset\"".getBytes("UTF-8"))
      val reminderBody = s"""{"data":"$dataB64","dueTime":"1h","period":""}"""
      httpPost(s"http://localhost:$appPort/actors/Counter/$id/method/remind/scheduled-reset", reminderBody)
      val resp = httpPost(actorUrl(id, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)
    }

  test("actor: timer callback increments counter — state read/written via real sidecar"):
    withContainers { _ =>
      val id = uniqueActorId()
      httpPost(actorUrl(id, "increment"), """{"amount":10}""")
      val dataB64   = java.util.Base64.getEncoder.nn.encodeToString("""{"amount":1}""".getBytes("UTF-8"))
      val timerBody = s"""{"data":"$dataB64","dueTime":"500ms","period":""}"""
      httpPost(s"http://localhost:$appPort/actors/Counter/$id/method/timer/auto-increment", timerBody)
      val resp = httpPost(actorUrl(id, "get"), "null")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 11)
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
