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

/** JVM bring-up for the shared [[InvokeItTest]] — the JVM implementation of the cross-platform `InvokeHarness` (the JS
  * one lives in test/js). Supplies the [[InvokeScenarios]] hooks `serverAppId` / `retrying` and the [[withDapr]]
  * boundary ([[DaprItFixture]]).
  *
  * Service invocation needs a reachable target, so — unlike the direct-call [[SharedDaprItSuite]] suites — this owns a
  * two-phase bring-up: a host-side [[DaprAppServer]] (registering the echo / echo-int / double routes the scenarios
  * call) is started and exposed to Docker BEFORE the sidecar, which is then pointed back at it (the same pattern as
  * [[ActorCapabilityServerTest]]).
  */
@scala.caps.assumeSafe
trait InvokeHarness extends TestContainersForAll, DaprServerTestBase, DaprItFixture, JvmItPolling:
  self: FunSuite =>

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
    eventually("sidecar healthz", timeoutMs = maxMs, intervalMs = 200) {
      try
        val conn = java.net.URI.create(url).toURL.nn.openConnection().asInstanceOf[java.net.HttpURLConnection]
        conn.setRequestMethod("GET")
        conn.setConnectTimeout(1000)
        conn.setReadTimeout(2000)
        conn.connect()
        val code = conn.getResponseCode
        conn.disconnect()
        Option.when(code == 204)(())
      catch case _: Exception => None
    }

  override def withDapr(body: DaprCapability ?=> Unit): Unit =
    withContainers { c => Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint)(body) }
