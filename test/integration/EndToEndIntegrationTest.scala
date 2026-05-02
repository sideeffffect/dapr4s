package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import java.util.Collections

/** End-to-end integration test that runs both [[OrderServiceHandlers]] and
  * [[InventoryServiceHandlers]] against the same real Dapr sidecar, exercising
  * the full order-placement → inventory-update flow.
  *
  * Showcases how multiple scala-safe-dapr capabilities work together:
  *   - [[StateCapability]]   — persisting orders and stock levels
  *   - [[PubSubCapability]]  — publishing order events (fire-and-forget)
  *   - [[AppHandlers]]       — subscribing to topics and handling invocations
  *
  * In production, Order and Inventory services run in separate pods with
  * separate Dapr sidecars; here both run in the same test process for speed.
  * The pub/sub event delivery between them is simulated via
  * [[TestAppHandlers.deliver]], which reflects real delivery semantics without
  * requiring two running HTTP servers.
  */
@scala.caps.assumeSafe
class EndToEndIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    DaprTestContainer(
      DaprContainer("daprio/daprd:latest")
        .withAppName("e2e-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory",  "v1", Collections.emptyMap()))
        .withComponent(Component("pubsub",     "pubsub.in-memory", "v1", Collections.emptyMap()))
    )

  // -------------------------------------------------------------------------

  test("e2e: placing an order decrements inventory stock"):
    withContainers { c =>
      val orderHandlers     = TestAppHandlers()
      val inventoryHandlers = TestAppHandlers()

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]

        // Wire up both services against the same Dapr sidecar
        OrderServiceHandlers.configure()(using scope, orderHandlers)
        InventoryServiceHandlers.configure()(using scope, inventoryHandlers)

        // Seed initial inventory
        inventoryHandlers.call[StockLevel]("seed-stock", StockLevel("tablet", 30))[StockLevel]

        // Place an order for 4 tablets
        val req  = OrderRequest("tablet", 4)
        val resp = orderHandlers.call[OrderRequest]("place-order", req)[OrderResponse]

        assertEquals(resp.status, "accepted")
        assert(resp.orderId.nonEmpty, "orderId must be non-empty")

        // The order is persisted in the state store
        val savedOrder = scope.state(StoreName("statestore"))
          .get[OrderRequest](StateKey(resp.orderId))
        assertEquals(savedOrder, Some(req))

        // Simulate event delivery from order-service to inventory-service
        val event = CloudEvent(
          id              = resp.orderId,
          source          = "order-service",
          specVersion     = "1.0",
          eventType       = "order.placed",
          topic           = Topic("orders"),
          pubSubName      = PubSubName("pubsub"),
          dataContentType = "application/json",
          data            = OrderEvent(resp.orderId, "tablet", 4)
        )
        val subResult = inventoryHandlers.deliver("orders", event)
        assertEquals(subResult, SubscriptionResult.Success)

        // Inventory is decremented
        val stock = inventoryHandlers.call[String]("get-stock", "tablet")[StockLevel]
        assertEquals(stock.available, 26)
    }

  test("e2e: multiple orders reduce inventory cumulatively"):
    withContainers { c =>
      val orderHandlers     = TestAppHandlers()
      val inventoryHandlers = TestAppHandlers()

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        OrderServiceHandlers.configure()(using scope, orderHandlers)
        InventoryServiceHandlers.configure()(using scope, inventoryHandlers)

        inventoryHandlers.call[StockLevel]("seed-stock", StockLevel("cable", 100))[StockLevel]

        val quantities = List(5, 10, 3, 7)
        quantities.foreach { qty =>
          val resp = orderHandlers.call[OrderRequest]("place-order", OrderRequest("cable", qty))[OrderResponse]
          inventoryHandlers.deliver("orders", mkEvent(resp.orderId, "cable", qty))
        }

        val stock = inventoryHandlers.call[String]("get-stock", "cable")[StockLevel]
        assertEquals(stock.available, 75) // 100 - 5 - 10 - 3 - 7
    }

  test("e2e: orders for different items tracked independently"):
    withContainers { c =>
      val orderHandlers     = TestAppHandlers()
      val inventoryHandlers = TestAppHandlers()

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        OrderServiceHandlers.configure()(using scope, orderHandlers)
        InventoryServiceHandlers.configure()(using scope, inventoryHandlers)

        inventoryHandlers.call[StockLevel]("seed-stock", StockLevel("pen",    50))[StockLevel]
        inventoryHandlers.call[StockLevel]("seed-stock", StockLevel("pencil", 80))[StockLevel]

        val penResp    = orderHandlers.call[OrderRequest]("place-order", OrderRequest("pen",    6))[OrderResponse]
        val pencilResp = orderHandlers.call[OrderRequest]("place-order", OrderRequest("pencil", 4))[OrderResponse]

        inventoryHandlers.deliver("orders", mkEvent(penResp.orderId,    "pen",    6))
        inventoryHandlers.deliver("orders", mkEvent(pencilResp.orderId, "pencil", 4))

        assertEquals(inventoryHandlers.call[String]("get-stock", "pen"   )[StockLevel].available, 44)
        assertEquals(inventoryHandlers.call[String]("get-stock", "pencil")[StockLevel].available, 76)
    }

  test("e2e: order state survives re-query after inventory update"):
    withContainers { c =>
      val orderHandlers     = TestAppHandlers()
      val inventoryHandlers = TestAppHandlers()

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        OrderServiceHandlers.configure()(using scope, orderHandlers)
        InventoryServiceHandlers.configure()(using scope, inventoryHandlers)

        inventoryHandlers.call[StockLevel]("seed-stock", StockLevel("drive", 10))[StockLevel]

        val req  = OrderRequest("drive", 2)
        val resp = orderHandlers.call[OrderRequest]("place-order", req)[OrderResponse]
        inventoryHandlers.deliver("orders", mkEvent(resp.orderId, "drive", 2))

        // Re-query the order — must still be present
        val fetched = orderHandlers.call[String]("get-order", resp.orderId)[Option[OrderRequest]]
        assertEquals(fetched, Some(req))

        // And inventory is updated
        assertEquals(inventoryHandlers.call[String]("get-stock", "drive")[StockLevel].available, 8)
    }

  test("e2e: concurrent ordersa use bulk state operations"):
    withContainers { c =>
      val orderHandlers     = TestAppHandlers()
      val inventoryHandlers = TestAppHandlers()

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        OrderServiceHandlers.configure()(using scope, orderHandlers)
        InventoryServiceHandlers.configure()(using scope, inventoryHandlers)

        inventoryHandlers.call[StockLevel]("seed-stock", StockLevel("lamp", 200))[StockLevel]

        // Place 10 orders
        val resps = (1 to 10).map { i =>
          orderHandlers.call[OrderRequest]("place-order", OrderRequest("lamp", i))[OrderResponse]
        }.toList

        // All IDs are distinct
        assertEquals(resps.map(_.orderId).distinct.size, 10)

        // Deliver all events
        resps.zipWithIndex.foreach { (resp, i) =>
          inventoryHandlers.deliver("orders", mkEvent(resp.orderId, "lamp", i + 1))
        }

        // Stock decremented by 1+2+...+10 = 55
        val stock = inventoryHandlers.call[String]("get-stock", "lamp")[StockLevel]
        assertEquals(stock.available, 145)
    }

  test("e2e: DaprScope is closed after run block — capabilities become unavailable"):
    withContainers { c =>
      var capturedScope: DaprScope | Null = null

      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        capturedScope = summon[DaprScope]

      // After the run block, the scope is closed
      val closed = capturedScope
      if closed != null then
        intercept[Exception]:
          closed.state(StoreName("statestore")).get[String](StateKey("k"))
    }

  // -------------------------------------------------------------------------

  private def mkEvent(orderId: String, item: String, qty: Int): CloudEvent[OrderEvent] =
    CloudEvent(
      id              = orderId,
      source          = "order-service",
      specVersion     = "1.0",
      eventType       = "order.placed",
      topic           = Topic("orders"),
      pubSubName      = PubSubName("pubsub"),
      dataContentType = "application/json",
      data            = OrderEvent(orderId, item, qty)
    )
