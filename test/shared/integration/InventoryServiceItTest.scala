package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[InventoryServiceApp]] integration suite — a SINGLE cross-platform file over the per-platform [[ServiceHarness]].
  * Hosts the inventory app behind a real [[dapr4s.internal.DaprAppServer]]; pub/sub delivery is the harness's direct
  * CloudEvent POST to the subscription route (the same envelope Dapr sends), and stock lives in the real `state.redis`
  * sidecar with decrements guarded by `lock.redis`.
  */
@scala.caps.assumeSafe
class InventoryServiceItTest extends FunSuite, ServiceHarness:

  private val Pubsub = InventoryServiceApp.PubSubComp.value
  private val Topic = InventoryServiceApp.OrdersTopic.value

  test("inventory service: get-stock returns default when no stock seeded"):
    withService(InventoryServiceApp()) {
      val stock = invoke[String, StockLevel]("get-stock", "widget")
      assertEquals(stock.item, "widget")
      assertEquals(stock.available, InventoryServiceApp.DefaultStock)
    }

  test("inventory service: seed-stock sets explicit level"):
    withService(InventoryServiceApp()) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("gadget", 42))
      assertEquals(invoke[String, StockLevel]("get-stock", "gadget").available, 42)
    }

  test("inventory service: order event decrements stock"):
    withService(InventoryServiceApp()) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("monitor", 20))
      val result = deliver(Topic, Pubsub, OrderEvent("order-1", "monitor", 5))
      assert(result.contains("SUCCESS"), s"expected SUCCESS, got: $result")
      assertEquals(invoke[String, StockLevel]("get-stock", "monitor").available, 15)
    }

  test("inventory service: multiple order events accumulate correctly"):
    withService(InventoryServiceApp()) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("widget", 50))
      deliver(Topic, Pubsub, OrderEvent("o1", "widget", 10))
      deliver(Topic, Pubsub, OrderEvent("o2", "widget", 15))
      deliver(Topic, Pubsub, OrderEvent("o3", "widget", 5))
      assertEquals(invoke[String, StockLevel]("get-stock", "widget").available, 20)
    }

  test("inventory service: stock never goes below zero"):
    withService(InventoryServiceApp()) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("rare-item", 3))
      deliver(Topic, Pubsub, OrderEvent("o1", "rare-item", 2))
      deliver(Topic, Pubsub, OrderEvent("o2", "rare-item", 5))
      assertEquals(invoke[String, StockLevel]("get-stock", "rare-item").available, 0)
    }

  test("inventory service: independent items do not interfere"):
    withService(InventoryServiceApp()) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("alpha", 10))
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("beta", 20))
      deliver(Topic, Pubsub, OrderEvent("o1", "alpha", 3))
      deliver(Topic, Pubsub, OrderEvent("o2", "beta", 7))
      assertEquals(invoke[String, StockLevel]("get-stock", "alpha").available, 7)
      assertEquals(invoke[String, StockLevel]("get-stock", "beta").available, 13)
    }

  test("inventory service: subscription and invoke routes are declared in the app"):
    withService(InventoryServiceApp()) {
      val app = InventoryServiceApp()
      assert(app.subscriptions.exists(_.topic.value == "orders"))
      assert(app.invokeRoutes.exists(_.methodName.value == "get-stock"))
      assert(app.invokeRoutes.exists(_.methodName.value == "seed-stock"))
    }
