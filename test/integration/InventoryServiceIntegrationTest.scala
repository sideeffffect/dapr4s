package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import dapr.safe.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[InventoryServiceHandlers]] against a real Dapr sidecar, dispatched through a real
  * [[dapr.safe.internal.DaprAppServer]] HTTP server.
  *
  * Each test starts a real HTTP server wrapping the handler app. Pub/sub delivery is simulated by POSTing a
  * CloudEvent JSON envelope directly to the subscription route — the same format Dapr would use in production.
  *
  * Uses `lock.redis` (backed by a Redis Testcontainer) because `lock.in-memory` does not exist in Dapr 1.17 — the only
  * supported distributed-lock component is Redis. Redis and daprd are placed on a shared Docker network so the sidecar
  * can reach Redis via the `redis` network alias.
  */
@scala.caps.assumeSafe
class InventoryServiceIntegrationTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  private var extraRedis: GenericContainer | Null = null
  private var extraNetwork: Network | Null = null

  override def afterAll(): Unit =
    super.afterAll()
    val r = extraRedis
    if r != null then r.stop()
    val n = extraNetwork
    if n != null then n.close()

  override def startContainers(): DaprTestContainer =
    val network = Network.newNetwork()
    extraNetwork = network

    val redis = GenericContainer(dockerImage = "redis:7-alpine", exposedPorts = Seq(6379))
    redis.container.withNetwork(network)
    redis.container.withNetworkAliases("redis")
    redis.start()
    extraRedis = redis

    val c = DaprTestContainer(
      DaprContainer("daprio/daprd:1.17.0")
        .withNetwork(network)
        .withAppName("inventory-service-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("pubsub", "pubsub.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("lockstore", "lock.redis", "v1", java.util.Map.of("redisHost", "redis:6379"))),
    )
    c.start()
    c

  // -------------------------------------------------------------------------

  test("inventory service: get-stock returns default when no stock seeded"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)
        withServer(app) { port =>
          val stock = invokeMethod[String, StockLevel](port, "get-stock", "widget")
          assertEquals(stock.item, "widget")
          assertEquals(stock.available, InventoryServiceHandlers.DefaultStock)
        }
    }

  test("inventory service: seed-stock sets explicit level"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("gadget", 42))
          val stock = invokeMethod[String, StockLevel](port, "get-stock", "gadget")
          assertEquals(stock.available, 42)
        }
    }

  test("inventory service: order event decrements stock"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)
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
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)
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
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("rare-item", 3))

          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o1", "rare-item", 2))
          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o2", "rare-item", 5))

          val stock = invokeMethod[String, StockLevel](port, "get-stock", "rare-item")
          assertEquals(stock.available, 0)
        }
    }

  test("inventory service: independent items do not interfere"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)
        withServer(app) { port =>
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("alpha", 10))
          invokeMethod[StockLevel, StockLevel](port, "seed-stock", StockLevel("beta", 20))

          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o1", "alpha", 3))
          deliverCloudEvent(port, "orders", "pubsub", OrderEvent("o2", "beta", 7))

          val alpha = invokeMethod[String, StockLevel](port, "get-stock", "alpha")
          val beta  = invokeMethod[String, StockLevel](port, "get-stock", "beta")

          assertEquals(alpha.available, 7)
          assertEquals(beta.available, 13)
        }
    }

  test("inventory service: all routes are declared in the app"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)

        assert(app.subscriptions.exists(_.topic.value == "orders"))
        assert(app.invocations.exists(_.methodName.value == "get-stock"))
        assert(app.invocations.exists(_.methodName.value == "seed-stock"))
    }
