package dapr4s.test.integration

import dapr4s.*
import dapr4s.internal.DaprAppServer
import dapr4s.test.unit.DaprServerTestBase
import dapr4s.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny
import java.util.Collections

/** Integration tests for [[ServiceInvocationCapability]] using the self-invoke pattern.
  *
  * A [[DaprAppServer]] registers several [[InvocationRoute]] handlers. The Dapr sidecar is configured to route to the
  * same app server. Tests then call [[ServiceInvocationCapability.invoke]] via the sidecar, which proxies the request
  * back to the app — exercising the full sidecar ↔ app invocation path.
  *
  * Because the sidecar needs a reachable target, the app server must be started before the sidecar (same two-phase
  * pattern as [[ActorCapabilityServerTest]]).
  */
@scala.caps.assumeSafe
class ServiceInvocationServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private var appServerThread: Option[Thread] = None

  override def afterAll(): Unit =
    super.afterAll()
    appServerThread.foreach { t => t.interrupt(); t.join(2000) }

  // The set of routes registered on the app server used for all tests in this suite.
  private val echoApp = DaprApp(
    invocations = List(
      InvocationRoute[String, String](MethodName("echo")) { s => s },
      InvocationRoute[IncrRequest, CounterState](MethodName("double")) { req =>
        CounterState(req.amount * 2)
      },
    ),
  )

  override def startContainers(): DaprTestContainer =
    // Make the host-side app server reachable from inside Docker containers.
    org.testcontainers.Testcontainers.exposeHostPorts(appPort)

    // Start the app server BEFORE the sidecar so the sidecar can call /dapr/config and route invocations.
    val server = new DaprAppServer(echoApp)
    appServerThread = Some(Thread.ofVirtual().start(() => server.startAndBlock(appPort)))
    waitForPort(appPort, 5000)

    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(org.testcontainers.containers.Network.SHARED)
        .withAppName("svc-invoke-test")
        .withAppPort(appPort)
        .withAppChannelAddress("host.testcontainers.internal")
        .withComponent(
          Component("statestore", "state.in-memory", "v1", Collections.emptyMap()),
        ),
    )
    c.start()

    // Wait for the sidecar to become fully healthy before running tests.
    waitForSidecarHealth(c.httpEndpoint.getPort)

    c

  private def waitForSidecarHealth(sidecarPort: Int, maxMs: Int = 30000): Unit =
    val url = s"http://localhost:$sidecarPort/v1.0/healthz"
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
        lastMsg = s"status=$code"
        if code == 204 then return
      catch
        case e: java.net.SocketTimeoutException =>
          lastMsg = "timeout"
        case e: Exception =>
          lastMsg = Option(e.getMessage).getOrElse(e.getClass.getName)
      Thread.sleep(200)
    throw RuntimeException(s"Sidecar not healthy after ${maxMs}ms — last=$lastMsg")

  private val selfAppId = AppId("svc-invoke-test")

  // ---- POST invocations -------------------------------------------------------

  test("invoke: POST self-invocation round-trips string payload"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoker = summon[DaprCapability].invoker
        val result = invoker.invoke(selfAppId, MethodName("echo"), "hello")[String]
        assertEquals(result, "hello")
    }

  test("invoke: POST self-invocation with structured data"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoker = summon[DaprCapability].invoker
        val result = invoker.invoke(selfAppId, MethodName("double"), IncrRequest(5))[CounterState]
        assertEquals(result, CounterState(10))
    }

  test("invoke: POST self-invocation returns correct structured response for another value"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val invoker = summon[DaprCapability].invoker
        val result = invoker.invoke(selfAppId, MethodName("double"), IncrRequest(7))[CounterState]
        assertEquals(result, CounterState(14))
    }
