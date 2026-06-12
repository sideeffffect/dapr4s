//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import io.dapr.testcontainers.{Component, DaprContainer}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.lifecycle.Andable.AndableOps
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Tests for [[ConfigurationCapability]] against a real `configuration.redis` component — the JVM twin of
  * [[ConfigurationJsIntegrationTest]] (same keys, same `value||version` seeding, same assertions).
  *
  * Dapr has no in-memory configuration store, so this uses Redis on a shared Docker network (like
  * [[InventoryServiceIntegrationTest]]'s `lock.redis`). Keys are seeded by `redis-cli MSET` inside the Redis container
  * — the in-container equivalent of the JS harness's `docker exec ... redis-cli MSET`; Dapr's redis configuration store
  * splits `value||version` into value + version.
  */
@scala.caps.assumeSafe
class ConfigurationCapabilityServerTest extends FunSuite with TestContainersForAll:

  type Containers = GenericContainer and DaprTestContainer

  private val Store = ConfigurationStoreName("configstore")

  override def startContainers(): GenericContainer and DaprTestContainer =
    val network = Network.newNetwork()

    val redis = GenericContainer(
      dockerImage = "redis:7-alpine",
      exposedPorts = Seq(6379),
      waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
    )
    redis.container.withNetwork(network)
    redis.container.withNetworkAliases("redis")
    redis.start()

    // Seed before daprd reads: Dapr's redis configuration store stores "value||version".
    val seed = redis.container.execInContainer(
      "redis-cli",
      "MSET",
      "dapr4s-cfg-a",
      "alpha||v1",
      "dapr4s-cfg-b",
      "beta||v2",
    )
    assertEquals(seed.getExitCode, 0, s"redis MSET failed: ${seed.getStderr}")

    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("configuration-server-test")
        .withAppPort(0)
        .withComponent(
          Component("configstore", "configuration.redis", "v1", java.util.Map.of("redisHost", "redis:6379")),
        )
        .dependsOn(redis.container),
    )
    c.start()
    redis and c

  test("configuration: get returns the seeded items with values and versions"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.configuration(Store) {
          val keyA = ConfigurationKey("dapr4s-cfg-a")
          val keyB = ConfigurationKey("dapr4s-cfg-b")
          val items = ConfigurationCapability.get(Seq(keyA, keyB))
          val a = items.getOrElse(keyA, fail(s"missing $keyA in $items"))
          val b = items.getOrElse(keyB, fail(s"missing $keyB in $items"))
          assertEquals(a.value, ConfigurationValue("alpha"))
          assertEquals(a.version, ConfigurationVersion("v1"))
          assertEquals(b.value, ConfigurationValue("beta"))
          assertEquals(b.version, ConfigurationVersion("v2"))
        }
    }

  test("configuration: get for an unknown key returns no item for it"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.configuration(Store) {
          val absent = ConfigurationKey("dapr4s-cfg-absent")
          val items = ConfigurationCapability.get(Seq(absent))
          assertEquals(items.get(absent), None)
        }
    }
