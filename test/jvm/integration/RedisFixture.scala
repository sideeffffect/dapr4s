//> using target.platform "jvm"
package dapr4s.test.integration

import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/** Mixin for the server-delivery integration suites whose bespoke bring-up (a host [[dapr4s.internal.DaprAppServer]]
  * the sidecar must reach, two-phase actor/workflow startup, multiple in-test servers) cannot use [[SharedDaprItSuite]],
  * but which still need their Dapr components on a real Redis (matching the JS harness — "redis everywhere").
  *
  * It owns a raw Redis container (managed outside testcontainers-scala's `Containers` so suites keep their existing
  * `Containers` type and unchanged test bodies) and renders the canonical shared component set
  * (scripts/it/components) so suites feed daprd the SAME manifests the JS harness and [[SharedDaprItSuite]] use, via
  * `rendered.component("statestore")` etc.
  */
trait RedisFixture extends TestContainersForAll:
  self: FunSuite =>

  private var redis: GenericContainer[?] | Null = null

  /** Start Redis (alias `redis`, the rendered redisHost) on `network` and render the shared components. Call from
    * `startContainers`; pass `Network.SHARED` for the two-phase host-server suites, `Network.newNetwork()` otherwise.
    */
  protected def startRedis(network: Network): JvmItComponents.Rendered =
    val r = GenericContainer(DockerImageName.parse("redis:7-alpine"))
      .nn
      .withNetwork(network)
      .nn
      .withNetworkAliases(JvmItComponents.RedisAlias)
      .nn
      .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
      .nn
    r.start()
    redis = r
    JvmItComponents.render()

  /** Seed the configuration items both harnesses use (only the configuration suite needs this). */
  protected def seedConfig(): Unit =
    val r = redis
    require(r != null, "startRedis must be called before seedConfig")
    val args = Array("redis-cli", "MSET") ++ JvmItComponents.SeededConfig.flatMap((k, v) => List(k, v))
    val res = r.nn.execInContainer(args*)
    assertEquals(res.getExitCode, 0, s"redis MSET failed: ${res.getStderr}")

  override def afterAll(): Unit =
    super.afterAll()
    val r = redis
    if r != null then r.nn.stop()
