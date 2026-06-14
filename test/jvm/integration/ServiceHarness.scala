//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.lifecycle.Andable.AndableOps
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import munit.FunSuite

/** JVM implementation of the service-suite [[ServiceHarnessApi]] (the JS twin lives in test/js): one Redis + one daprd
  * loading the canonical statestore/pubsub/lockstore components, and a fresh per-test [[dapr4s.internal.DaprAppServer]]
  * (via [[DaprServerTestBase.withServer]]) poked directly over HTTP. The sidecar uses `withAppPort(0)` (no app
  * channel), so a handler's published event is never delivered back — pub/sub delivery is the test's direct CloudEvent
  * POST.
  */
@scala.caps.assumeSafe
trait ServiceHarness extends ServiceHarnessApi, TestContainersForAll, DaprServerTestBase:
  self: FunSuite =>

  override type Containers = GenericContainer and DaprTestContainer

  override def startContainers(): GenericContainer and DaprTestContainer =
    val network = Network.newNetwork()
    val redis = GenericContainer(
      dockerImage = "redis:7-alpine",
      exposedPorts = Seq(6379),
      waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
    )
    redis.container.withNetwork(network)
    redis.container.withNetworkAliases(ItNames.RedisAlias)
    redis.start()

    val res = JvmItComponents.render()
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("service-it")
        .withAppPort(0)
        .withComponent(res.component("statestore"))
        .withComponent(res.component("pubsub"))
        .withComponent(res.component("lockstore"))
        .dependsOn(redis.container),
    )
    c.start()
    redis and c

  private var portRef: Int = -1

  override protected def invokeRaw(path: String, reqBody: String): (Int, String) =
    httpPostWithCode(s"http://localhost:$portRef/$path", reqBody)

  override protected def withService(appOf: DaprCapability ?=> DaprApp)(body: DaprCapability ?=> Unit): Unit =
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = appOf(using scope)
        withServer(app) { port =>
          portRef = port
          body(using scope)
        }
    }
