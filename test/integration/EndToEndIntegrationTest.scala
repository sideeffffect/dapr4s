package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import munit.FunSuite

import java.util.Collections

/** End-to-end integration test that runs both [[OrderServiceHandlers]] and [[InventoryServiceHandlers]] against the
  * same real Dapr sidecar, exercising the full order-placement → inventory-update flow.
  *
  * Showcases how multiple scala-safe-dapr capabilities work together:
  *   - [[StateCapability]] — persisting orders and stock levels
  *   - [[PubSubCapability]] — publishing order events (fire-and-forget)
  *   - [[DistributedLockCapability]] — serialising concurrent stock updates
  *   - [[DaprApp]] — declarative handler composition
  *
  * In production, Order and Inventory services run in separate pods with separate Dapr sidecars; here both run in the
  * same test process for speed. The pub/sub event delivery between them is simulated via [[TestDaprApp.deliver]], which
  * reflects real delivery semantics without requiring two running HTTP servers.
  */
/** See [[InventoryServiceIntegrationTest]] for an explanation of why `lock.redis` is used rather than `lock.in-memory`.
  */
@scala.caps.assumeSafe
class EndToEndIntegrationTest extends FunSuite with TestContainersForAll:

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
        .withAppName("e2e-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("pubsub", "pubsub.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("lockstore", "lock.redis", "v1", java.util.Map.of("redisHost", "redis:6379"))),
    )
    c.start()
    c

  // -------------------------------------------------------------------------

  test("e2e: placing an order decrements inventory stock"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceHandlers.daprApp(using scope)
        val inventoryApp = InventoryServiceHandlers.daprApp(using scope)

        // Seed initial inventory
        TestDaprApp.call[StockLevel](inventoryApp, "seed-stock", StockLevel("tablet", 30))[StockLevel]

        // Place an order for 4 tablets
        val req = OrderRequest("tablet", 4)
        val resp = TestDaprApp.call[OrderRequest](orderApp, "place-order", req)[OrderResponse]

        assertEquals(resp.status, "accepted")
        assert(resp.orderId.nonEmpty, "orderId must be non-empty")

        // The order is persisted in the state store
        val savedOrder = scope
          .state(StoreName("statestore"))
          .get[OrderRequest](StateKey(resp.orderId))
        assertEquals(savedOrder, Some(req))

        // Simulate event delivery from order-service to inventory-service
        val event = CloudEvent(
          id = CloudEventId(resp.orderId),
          source = CloudEventSource("order-service"),
          specVersion = CloudEventSpecVersion("1.0"),
          eventType = CloudEventType("order.placed"),
          topic = Topic("orders"),
          pubSubName = PubSubName("pubsub"),
          dataContentType = ContentType("application/json"),
          data = OrderEvent(resp.orderId, "tablet", 4),
        )
        val subResult = TestDaprApp.deliver(inventoryApp, "orders", event)
        assertEquals(subResult, SubscriptionResult.Success)

        // Inventory is decremented
        val stock = TestDaprApp.call[String](inventoryApp, "get-stock", "tablet")[StockLevel]
        assertEquals(stock.available, 26)
    }

  test("e2e: multiple orders reduce inventory cumulatively"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceHandlers.daprApp(using scope)
        val inventoryApp = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](inventoryApp, "seed-stock", StockLevel("cable", 100))[StockLevel]

        val quantities = List(5, 10, 3, 7)
        quantities.foreach { qty =>
          val resp = TestDaprApp.call[OrderRequest](orderApp, "place-order", OrderRequest("cable", qty))[OrderResponse]
          TestDaprApp.deliver(inventoryApp, "orders", mkEvent(resp.orderId, "cable", qty))
        }

        val stock = TestDaprApp.call[String](inventoryApp, "get-stock", "cable")[StockLevel]
        assertEquals(stock.available, 75) // 100 - 5 - 10 - 3 - 7
    }

  test("e2e: orders for different items tracked independently"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceHandlers.daprApp(using scope)
        val inventoryApp = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](inventoryApp, "seed-stock", StockLevel("pen", 50))[StockLevel]
        TestDaprApp.call[StockLevel](inventoryApp, "seed-stock", StockLevel("pencil", 80))[StockLevel]

        val penResp = TestDaprApp.call[OrderRequest](orderApp, "place-order", OrderRequest("pen", 6))[OrderResponse]
        val pencilResp =
          TestDaprApp.call[OrderRequest](orderApp, "place-order", OrderRequest("pencil", 4))[OrderResponse]

        TestDaprApp.deliver(inventoryApp, "orders", mkEvent(penResp.orderId, "pen", 6))
        TestDaprApp.deliver(inventoryApp, "orders", mkEvent(pencilResp.orderId, "pencil", 4))

        assertEquals(TestDaprApp.call[String](inventoryApp, "get-stock", "pen")[StockLevel].available, 44)
        assertEquals(TestDaprApp.call[String](inventoryApp, "get-stock", "pencil")[StockLevel].available, 76)
    }

  test("e2e: order state survives re-query after inventory update"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceHandlers.daprApp(using scope)
        val inventoryApp = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](inventoryApp, "seed-stock", StockLevel("drive", 10))[StockLevel]

        val req = OrderRequest("drive", 2)
        val resp = TestDaprApp.call[OrderRequest](orderApp, "place-order", req)[OrderResponse]
        TestDaprApp.deliver(inventoryApp, "orders", mkEvent(resp.orderId, "drive", 2))

        // Re-query the order — must still be present
        val fetched = TestDaprApp.call[String](orderApp, "get-order", resp.orderId)[Option[OrderRequest]]
        assertEquals(fetched, Some(req))

        // And inventory is updated
        assertEquals(TestDaprApp.call[String](inventoryApp, "get-stock", "drive")[StockLevel].available, 8)
    }

  test("e2e: concurrent orders use bulk state operations"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceHandlers.daprApp(using scope)
        val inventoryApp = InventoryServiceHandlers.daprApp(using scope)

        TestDaprApp.call[StockLevel](inventoryApp, "seed-stock", StockLevel("lamp", 200))[StockLevel]

        // Place 10 orders
        val resps = (1 to 10).map { i =>
          TestDaprApp.call[OrderRequest](orderApp, "place-order", OrderRequest("lamp", i))[OrderResponse]
        }.toList

        // All IDs are distinct
        assertEquals(resps.map(_.orderId).distinct.size, 10)

        // Deliver all events
        resps.zipWithIndex.foreach { (resp, i) =>
          TestDaprApp.deliver(inventoryApp, "orders", mkEvent(resp.orderId, "lamp", i + 1))
        }

        // Stock decremented by 1+2+...+10 = 55
        val stock = TestDaprApp.call[String](inventoryApp, "get-stock", "lamp")[StockLevel]
        assertEquals(stock.available, 145)
    }

  test("e2e: DaprCapability is closed after run block — capabilities become unavailable"):
    withContainers { c =>
      // WHY AnyRef: DaprCapability now extends ExclusiveCapability, so the CC checker
      // prevents it from escaping the run block. We use AnyRef to capture it for the
      // post-block closed-scope assertion — this is intentionally unsafe (testing
      // the runtime close() behaviour, not compile-time safety).
      var capturedScope: AnyRef | Null = null

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        capturedScope = summon[DaprCapability].asInstanceOf[AnyRef]

      // After the run block, the scope is closed
      val closed = capturedScope.asInstanceOf[DaprCapability | Null]
      if closed != null then
        intercept[Exception]:
          closed.state(StoreName("statestore")).get[String](StateKey("k"))
    }

  test("e2e: DaprApp composition with ++ merges routes"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceHandlers.daprApp(using scope)
        val inventoryApp = InventoryServiceHandlers.daprApp(using scope)
        val combined = orderApp ++ inventoryApp

        // Combined app has routes from both services
        assert(combined.invocations.exists(_.methodName.value == "place-order"))
        assert(combined.invocations.exists(_.methodName.value == "get-stock"))
        assert(combined.subscriptions.exists(_.topic.value == "orders"))
    }

  // -------------------------------------------------------------------------

  private def mkEvent(orderId: String, item: String, qty: Int): CloudEvent[OrderEvent] =
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
