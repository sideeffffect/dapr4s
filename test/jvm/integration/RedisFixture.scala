//> using target.platform "jvm"
package dapr4s.test.integration

import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait

/** Mixin for the server-delivery integration suites whose bespoke bring-up (a host [[dapr4s.internal.DaprAppServer]]
  * the sidecar must reach, two-phase actor/workflow startup, multiple in-test servers) cannot use
  * [[SharedDaprItSuite]], but which still need their Dapr components on a real Redis (matching the JS harness — "redis
  * everywhere").
  *
  * It owns a Redis container (managed outside testcontainers-scala's `Containers`, so suites keep their existing
  * `Containers` type and unchanged test bodies) and renders the canonical shared component set (scripts/it/components)
  * so suites feed daprd the SAME manifests the JS harness and [[SharedDaprItSuite]] use, via
  * `rendered.component("statestore")` etc.
  */
trait RedisFixture extends TestContainersForAll:
  self: FunSuite =>

  private var redis: GenericContainer | Null = null

  /** Start Redis (alias `redis`, the rendered redisHost) on `network` and render the shared components. Call from
    * `startContainers`; pass `Network.SHARED` for the two-phase host-server suites, `Network.newNetwork()` otherwise.
    */
  protected def startRedis(network: Network): JvmItComponents.Rendered =
    val r = GenericContainer(
      dockerImage = "redis:7-alpine",
      exposedPorts = Seq(6379),
      waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
    )
    r.container.withNetwork(network)
    r.container.withNetworkAliases(JvmItComponents.RedisAlias)
    r.start()
    redis = r
    JvmItComponents.render()

  override def afterAll(): Unit =
    super.afterAll()
    val r = redis
    if r != null then r.stop()
