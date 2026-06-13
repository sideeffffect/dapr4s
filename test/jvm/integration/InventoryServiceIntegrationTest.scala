//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.lifecycle.Andable.AndableOps
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import munit.FunSuite

/** Integration tests for [[InventoryServiceApp]] against a real Dapr sidecar, dispatched through a real
  * [[dapr4s.internal.DaprAppServer]] HTTP server.
  *
  * Each test starts a real HTTP server wrapping the handler app. Pub/sub delivery is simulated by POSTing a CloudEvent
  * JSON envelope directly to the subscription route — the same format Dapr would use in production.
  *
  * Uses `lock.redis` (backed by a Redis Testcontainer) because `lock.in-memory` does not exist in Dapr 1.17 — the only
  * supported distributed-lock component is Redis. Redis and daprd are placed on a shared Docker network so the sidecar
  * can reach Redis via the `redis` network alias.
  */
@scala.caps.assumeSafe
class InventoryServiceIntegrationTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = GenericContainer and DaprTestContainer

  override def startContainers(): GenericContainer and DaprTestContainer =
    val network = Network.newNetwork()

    val redis = GenericContainer(
      dockerImage = "redis:7-alpine",
      exposedPorts = Seq(6379),
      waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
    )
    redis.container.withNetwork(network)
    redis.container.withNetworkAliases(JvmItComponents.RedisAlias)
    redis.start()

    val res = JvmItComponents.render()
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("inventory-service-test")
        .withAppPort(0)
        .withComponent(res.component("statestore"))
        .withComponent(res.component("pubsub"))
        .withComponent(res.component("lockstore"))
        .dependsOn(redis.container),
    )
    c.start()
    redis and c

  // -------------------------------------------------------------------------

  test("inventory service: get-stock returns default when no stock seeded"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)
        withServer(app) { port =>
          val stock = invokeMethod[String, StockLevel](port, "get-stock", "widget")
          assertEquals(stock.item, "widget")
          assertEquals(stock.available, InventoryServiceApp.DefaultStock)
        }
    }

  test("inventory service: seed-stock sets explicit level"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("gadget", 42))
          val stock = invokeMethod[String, StockLevel](port, "get-stock", "gadget")
          assertEquals(stock.available, 42)
        }
    }

  test("inventory service: order event decrements stock"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("monitor", 20))

          val result = deliverCloudEvent[OrderEvent](
            port,
            topic = "orders",
            pubsubName = "pubsub",
            data = OrderEvent("order-1", "monitor", 5),
          )
          assert(result.contains("SUCCESS"), s"expected SUCCESS, got: $result")

          val stock = invokeMethod[String, StockLevel](port, "get-stock", "monitor")
          assertEquals(stock.available, 15)
        }
    }

  test("inventory service: multiple order events accumulate correctly"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("widget", 50))

          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o1", "widget", 10))
          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o2", "widget", 15))
          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o3", "widget", 5))

          val stock = invokeMethod[String, StockLevel](port, "get-stock", "widget")
          assertEquals(stock.available, 20)
        }
    }

  test("inventory service: stock never goes below zero"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("rare-item", 3))

          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o1", "rare-item", 2))
          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o2", "rare-item", 5))

          val stock = invokeMethod[String, StockLevel](port, "get-stock", "rare-item")
          assertEquals(stock.available, 0)
        }
    }

  test("inventory service: independent items do not interfere"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("alpha", 10))
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("beta", 20))

          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o1", "alpha", 3))
          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o2", "beta", 7))

          val alpha = invokeMethod[String, StockLevel](port, "get-stock", "alpha")
          val beta = invokeMethod[String, StockLevel](port, "get-stock", "beta")

          assertEquals(alpha.available, 7)
          assertEquals(beta.available, 13)
        }
    }

  test("inventory service: all routes are declared in the app"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceApp()(using scope)

        assert(app.subscriptions.exists(_.topic.value == "orders"))
        assert(app.invokeRoutes.exists(_.methodName.value == "get-stock"))
        assert(app.invokeRoutes.exists(_.methodName.value == "seed-stock"))
    }
