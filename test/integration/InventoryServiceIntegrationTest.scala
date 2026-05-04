package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[InventoryServiceHandlers]] against a real Dapr sidecar.
  *
  * Demonstrates:
  *   - State management (get/save) via [[StateCapability]]
  *   - Pub/sub subscription handler via [[TestDaprApp.deliver]]
  *   - Default stock fallback logic
  *
  * Uses `lock.redis` (backed by a Redis Testcontainer) because `lock.in-memory` does not exist in Dapr 1.17 — the only
  * supported distributed-lock component is Redis. Redis and daprd are placed on a shared Docker network so the sidecar
  * can reach Redis via the `redis` network alias.
  */
@scala.caps.assumeSafe
class InventoryServiceIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  // Redis and network are managed here; DaprTestContainer lifecycle is managed by TestContainersForAll.
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

        val stock = TestDaprApp.call[String](app, "get-stock", "widget")[StockLevel]
        assertEquals(stock.item, "widget")
        assertEquals(stock.available, InventoryServiceHandlers.DefaultStock)
    }

  test("inventory service: seed-stock sets explicit level"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](app, "seed-stock", StockLevel("gadget", 42))[StockLevel]
        val stock = TestDaprApp.call[String](app, "get-stock", "gadget")[StockLevel]
        assertEquals(stock.available, 42)
    }

  test("inventory service: order event decrements stock"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)

        // Seed 20 units
        TestDaprApp.call[StockLevel](app, "seed-stock", StockLevel("monitor", 20))[StockLevel]

        // Deliver order event for 5 monitors
        val event = mkOrderEvent(orderId = "order-1", item = "monitor", qty = 5)
        val result = TestDaprApp.deliver(app, "orders", event)

        assertEquals(result, SubscriptionResult.Success)

        val stock = TestDaprApp.call[String](app, "get-stock", "monitor")[StockLevel]
        assertEquals(stock.available, 15)
    }

  test("inventory service: multiple order events accumulate correctly"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](app, "seed-stock", StockLevel("widget", 50))[StockLevel]

        TestDaprApp.deliver(app, "orders", mkOrderEvent("o1", "widget", 10))
        TestDaprApp.deliver(app, "orders", mkOrderEvent("o2", "widget", 15))
        TestDaprApp.deliver(app, "orders", mkOrderEvent("o3", "widget", 5))

        val stock = TestDaprApp.call[String](app, "get-stock", "widget")[StockLevel]
        assertEquals(stock.available, 20)
    }

  test("inventory service: stock never goes below zero"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](app, "seed-stock", StockLevel("rare-item", 3))[StockLevel]

        TestDaprApp.deliver(app, "orders", mkOrderEvent("o1", "rare-item", 2))
        TestDaprApp.deliver(app, "orders", mkOrderEvent("o2", "rare-item", 5)) // oversell

        val stock = TestDaprApp.call[String](app, "get-stock", "rare-item")[StockLevel]
        assertEquals(stock.available, 0)
    }

  test("inventory service: independent items do not interfere"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](app, "seed-stock", StockLevel("alpha", 10))[StockLevel]
        TestDaprApp.call[StockLevel](app, "seed-stock", StockLevel("beta", 20))[StockLevel]

        TestDaprApp.deliver(app, "orders", mkOrderEvent("o1", "alpha", 3))
        TestDaprApp.deliver(app, "orders", mkOrderEvent("o2", "beta", 7))

        val alpha = TestDaprApp.call[String](app, "get-stock", "alpha")[StockLevel]
        val beta = TestDaprApp.call[String](app, "get-stock", "beta")[StockLevel]

        assertEquals(alpha.available, 7)
        assertEquals(beta.available, 13)
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

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def mkOrderEvent(orderId: String, item: String, qty: Int): CloudEvent[OrderEvent] =
    CloudEvent(
      id = CloudEventId(orderId),
      source = CloudEventSource("order-service"),
      specVersion = CloudEventSpecVersion("1.0"),
      eventType = CloudEventType("order.placed"),
      topic = Topic("orders"),
      pubSubName = PubSubName("pubsub"),
      dataContentType = ContentType("application/json"),
      data = OrderEvent(orderId, item, qty),
    )
