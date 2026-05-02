package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Integration tests for [[InventoryServiceHandlers]] against a real Dapr sidecar.
  *
  * Demonstrates:
  *  - State management (get/save) via [[StateCapability]]
  *  - Pub/sub subscription handler via [[TestAppHandlers.deliver]]
  *  - Default stock fallback logic
  */
@scala.caps.assumeSafe
class InventoryServiceIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    DaprTestContainer(
      DaprContainer("daprio/daprd:latest")
        .withAppName("inventory-service-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory",  "v1", Collections.emptyMap()))
        .withComponent(Component("pubsub",     "pubsub.in-memory", "v1", Collections.emptyMap()))
    )

  // -------------------------------------------------------------------------

  test("inventory service: get-stock returns default when no stock seeded"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        val stock = handlers.call[String]("get-stock", "widget")[StockLevel]
        assertEquals(stock.item, "widget")
        assertEquals(stock.available, InventoryServiceHandlers.DefaultStock)
    }

  test("inventory service: seed-stock sets explicit level"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        handlers.call[StockLevel]("seed-stock", StockLevel("gadget", 42))[StockLevel]
        val stock = handlers.call[String]("get-stock", "gadget")[StockLevel]
        assertEquals(stock.available, 42)
    }

  test("inventory service: order event decrements stock"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        // Seed 20 units
        handlers.call[StockLevel]("seed-stock", StockLevel("monitor", 20))[StockLevel]

        // Deliver order event for 5 monitors
        val event = mkOrderEvent(orderId = "order-1", item = "monitor", qty = 5)
        val result = handlers.deliver("orders", event)

        assertEquals(result, SubscriptionResult.Success)

        val stock = handlers.call[String]("get-stock", "monitor")[StockLevel]
        assertEquals(stock.available, 15)
    }

  test("inventory service: multiple order events accumulate correctly"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        handlers.call[StockLevel]("seed-stock", StockLevel("widget", 50))[StockLevel]

        handlers.deliver("orders", mkOrderEvent("o1", "widget", 10))
        handlers.deliver("orders", mkOrderEvent("o2", "widget", 15))
        handlers.deliver("orders", mkOrderEvent("o3", "widget", 5))

        val stock = handlers.call[String]("get-stock", "widget")[StockLevel]
        assertEquals(stock.available, 20)
    }

  test("inventory service: stock never goes below zero"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        handlers.call[StockLevel]("seed-stock", StockLevel("rare-item", 3))[StockLevel]

        handlers.deliver("orders", mkOrderEvent("o1", "rare-item", 2))
        handlers.deliver("orders", mkOrderEvent("o2", "rare-item", 5)) // oversell

        val stock = handlers.call[String]("get-stock", "rare-item")[StockLevel]
        assertEquals(stock.available, 0)
    }

  test("inventory service: independent items do not interfere"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        handlers.call[StockLevel]("seed-stock", StockLevel("alpha", 10))[StockLevel]
        handlers.call[StockLevel]("seed-stock", StockLevel("beta",  20))[StockLevel]

        handlers.deliver("orders", mkOrderEvent("o1", "alpha", 3))
        handlers.deliver("orders", mkOrderEvent("o2", "beta",  7))

        val alpha = handlers.call[String]("get-stock", "alpha")[StockLevel]
        val beta  = handlers.call[String]("get-stock", "beta")[StockLevel]

        assertEquals(alpha.available, 7)
        assertEquals(beta.available, 13)
    }

  test("inventory service: subscriber and method handlers are registered"):
    withContainers { c =>
      val handlers = TestAppHandlers()
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprScope]
        InventoryServiceHandlers.configure()(using scope, handlers)

        assert(handlers.hasSubscriber("orders"))
        assert(handlers.hasMethod("get-stock"))
        assert(handlers.hasMethod("seed-stock"))
    }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def mkOrderEvent(orderId: String, item: String, qty: Int): CloudEvent[OrderEvent] =
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
