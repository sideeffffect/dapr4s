package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** End-to-end integration suite — a SINGLE cross-platform file over the per-platform [[ServiceHarness]] — running
  * [[OrderServiceApp]] and [[InventoryServiceApp]] together (merged into one [[dapr4s.internal.DaprAppServer]] via
  * `++`; their method/topic names are disjoint) against one real sidecar, exercising the order-placement →
  * inventory-update flow across State, Publish, Lock and Invoke.
  *
  * Order-service pub/sub delivery to the inventory service is the harness's direct CloudEvent POST (the server is not
  * the sidecar's app channel, so the order app's own fire-and-forget publish is not redelivered — the JVM behaviour the
  * suite has always relied on, now identical on JS).
  */
@scala.caps.assumeSafe
class EndToEndItTest extends FunSuite, ServiceHarness:

  private val Pubsub = OrderServiceApp.PubSubComp.value
  private val Topic = OrderServiceApp.OrdersTopic.value
  private def app(using DaprCapability): DaprApp = OrderServiceApp() ++ InventoryServiceApp()

  test("e2e: placing an order decrements inventory stock"):
    withService(app) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("tablet", 30))
      val req = OrderRequest("tablet", 4)
      val resp = invoke[OrderRequest, OrderResponse]("place-order", req)
      assertEquals(resp.status, "accepted")
      assert(resp.orderId.nonEmpty, "orderId must be non-empty")
      val savedOrder =
        summon[DaprCapability].state(OrderServiceApp.StateName).get[OrderRequest](StateStoreKey(resp.orderId))
      assertEquals(savedOrder, Some(req))
      deliver(Topic, Pubsub, OrderEvent(resp.orderId, "tablet", 4))
      assertEquals(invoke[String, StockLevel]("get-stock", "tablet").available, 26)
    }

  test("e2e: multiple orders reduce inventory cumulatively"):
    withService(app) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("cable", 100))
      List(5, 10, 3, 7).foreach { qty =>
        val resp = invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("cable", qty))
        deliver(Topic, Pubsub, OrderEvent(resp.orderId, "cable", qty))
      }
      assertEquals(invoke[String, StockLevel]("get-stock", "cable").available, 75)
    }

  test("e2e: orders for different items tracked independently"):
    withService(app) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("pen", 50))
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("pencil", 80))
      val penResp = invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("pen", 6))
      val pencilResp = invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("pencil", 4))
      deliver(Topic, Pubsub, OrderEvent(penResp.orderId, "pen", 6))
      deliver(Topic, Pubsub, OrderEvent(pencilResp.orderId, "pencil", 4))
      assertEquals(invoke[String, StockLevel]("get-stock", "pen").available, 44)
      assertEquals(invoke[String, StockLevel]("get-stock", "pencil").available, 76)
    }

  test("e2e: order state survives re-query after inventory update"):
    withService(app) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("drive", 10))
      val req = OrderRequest("drive", 2)
      val resp = invoke[OrderRequest, OrderResponse]("place-order", req)
      deliver(Topic, Pubsub, OrderEvent(resp.orderId, "drive", 2))
      assertEquals(invoke[String, Option[OrderRequest]]("get-order", resp.orderId), Some(req))
      assertEquals(invoke[String, StockLevel]("get-stock", "drive").available, 8)
    }

  test("e2e: many orders reduce inventory cumulatively"):
    withService(app) {
      invoke[StockLevel, StockLevel]("seed-stock", StockLevel("lamp", 200))
      val resps = (1 to 10).map(i => invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("lamp", i))).toList
      assertEquals(resps.map(_.orderId).distinct.size, 10)
      resps.zipWithIndex.foreach { (resp, i) => deliver(Topic, Pubsub, OrderEvent(resp.orderId, "lamp", i + 1)) }
      assertEquals(invoke[String, StockLevel]("get-stock", "lamp").available, 145)
    }

  test("e2e: DaprApp composition with ++ merges routes"):
    withService(app) {
      val combined = OrderServiceApp() ++ InventoryServiceApp()
      assert(combined.invokeRoutes.exists(_.methodName.value == "place-order"))
      assert(combined.invokeRoutes.exists(_.methodName.value == "get-stock"))
      assert(combined.subscriptions.exists(_.topic.value == "orders"))
    }
