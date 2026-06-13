//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.internal.DaprAppServer
import dapr4s.test.unit.DaprServerTestBase
import dapr4s.test.integration.apps.*
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM [[InvokeCapability]] integration suite: a thin shell over the shared [[InvokeScenarios]] (caller side: echo,
  * falsy-0, the derived [[EchoService]] facade and the non-existent-app error path). The JS twin
  * [[InvokeJsIntegrationTest]] runs the very same scenarios.
  *
  * Service invocation needs a reachable target, so — unlike the direct-call [[SharedDaprItSuite]] suites — this owns a
  * two-phase bring-up: a host-side [[DaprAppServer]] (registering the echo / echo-int / double routes the scenarios
  * call) is started and exposed to Docker BEFORE the sidecar, which is then pointed back at it (the same pattern as
  * [[ActorCapabilityServerTest]]). Replaces the former InvokeCapabilityServerTest + InvokeIntegrationTest.
  */
@scala.caps.assumeSafe
class InvokeItTest extends FunSuite, TestContainersForAll, DaprServerTestBase, InvokeScenarios:

  override type Containers = DaprTestContainer

  protected def serverAppId: AppId = AppId("svc-invoke-test")
  // The sidecar health is polled up front (waitForSidecarHealth), so no per-call retry is needed.
  protected def retrying[T](label: String)(body: => T): T = body

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private var appServerThread: Option[Thread] = None

  override def afterAll(): Unit =
    super.afterAll()
    appServerThread.foreach { t => t.interrupt(); t.join(2000) }

  // The routes the shared InvokeScenarios exercise — the same set JsItServerApp registers on JS.
  private val echoApp = DaprApp(
    invokeRoutes = List(
      InvokeRoute[String, String](InvokeMethodName("echo")) { s => s },
      InvokeRoute[Int, Int](InvokeMethodName("echo-int")) { i => i },
      InvokeRoute[IncrRequest, CounterState](InvokeMethodName("double")) { req => CounterState(req.amount * 2) },
    ),
  )

  override def startContainers(): DaprTestContainer =
    org.testcontainers.Testcontainers.exposeHostPorts(appPort)
    val server = new DaprAppServer(echoApp)
    appServerThread = Some(
      Thread.ofVirtual().start(() => server.startAndBlock(appPort, TestDapr.placeholderCapability)),
    )
    waitForPort(appPort, 5000)

    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(org.testcontainers.containers.Network.SHARED)
        .withAppName("svc-invoke-test")
        .withAppPort(appPort)
        .withAppChannelAddress("host.testcontainers.internal"),
    )
    c.start()
    waitForSidecarHealth(c.httpEndpoint.getPort)
    c

  private def waitForSidecarHealth(sidecarPort: Int, maxMs: Int = 30000): Unit =
    val url = s"http://localhost:$sidecarPort/v1.0/healthz"
    val deadline = System.currentTimeMillis() + maxMs
    while System.currentTimeMillis() < deadline do
      try
        val conn = java.net.URI.create(url).toURL.nn.openConnection().asInstanceOf[java.net.HttpURLConnection]
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(1000)
        conn.setReadTimeout(2000)
        conn.connect()
        val code = conn.getResponseCode
        conn.disconnect()
        if code == 204 then return
      catch case _: Exception => ()
      Thread.sleep(200)
    throw RuntimeException(s"Sidecar not healthy after ${maxMs}ms")

  private def withDapr(body: DaprCapability ?=> Unit): Unit =
    withContainers { c => Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint)(body) }

  test("invoke: echo roundtrip via the app server")(withDapr(echoRoundtrip))
  test("invoke: falsy body 0 reaches the handler")(withDapr(falsyZeroBodyRoundtrips))
  test("invoke: derived EchoService facade calls the matching server routes")(withDapr(derivedEchoServiceFacade))
  test("invoke: invoking a non-existent app throws")(withDapr(nonexistentAppThrows))
