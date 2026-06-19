package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[OrderServiceApp]] integration suite — a SINGLE cross-platform file over the per-platform [[ServiceHarness]]. Each
  * test hosts the order app behind a real [[dapr4s.internal.DaprAppServer]] and drives it over HTTP (the full encode →
  * HTTP → decode path), with order state persisted in the real `state.redis` sidecar.
  */
@scala.caps.assumeSafe
class OrderServiceItTest extends FunSuite, ServiceHarness:

  test("order service: place-order saves order to state store"):
    withService(OrderServiceApp()) {
      val req = OrderRequest("laptop", 2)
      val resp = invoke[OrderRequest, OrderResponse]("place-order", req)
      assertEquals(resp.status, "accepted")
      assert(resp.orderId.nonEmpty)
      val saved = summon[DaprCapability].state(OrderServiceApp.StateName).get[OrderRequest](StateStoreKey(resp.orderId))
      assertEquals(saved, Some(req))
    }

  test("order service: get-order retrieves a previously placed order"):
    withService(OrderServiceApp()) {
      val req = OrderRequest("keyboard", 1)
      val resp = invoke[OrderRequest, OrderResponse]("place-order", req)
      val fetched = invoke[String, Option[OrderRequest]]("get-order", resp.orderId)
      assertEquals(fetched, Some(req))
    }

  test("order service: get-order returns None for unknown order ID"):
    withService(OrderServiceApp()) {
      assertEquals(invoke[String, Option[OrderRequest]]("get-order", "no-such-order-id"), None)
    }

  test("order service: two consecutive orders get distinct IDs"):
    withService(OrderServiceApp()) {
      val r1 = invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("mouse", 1))
      val r2 = invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("monitor", 2))
      assertNotEquals(r1.orderId, r2.orderId)
      assertEquals(r1.status, "accepted")
      assertEquals(r2.status, "accepted")
    }

  test("order service: place-order (which publishes fire-and-forget) succeeds"):
    withService(OrderServiceApp()) {
      val resp = invoke[OrderRequest, OrderResponse]("place-order", OrderRequest("headphones", 3))
      assertEquals(resp.status, "accepted")
    }

  test("order service: bulk orders all land in state store"):
    withService(OrderServiceApp()) {
      val items = List("pencil", "ruler", "eraser", "notebook", "stapler")
      val ids = items.map(item => invoke[OrderRequest, OrderResponse]("place-order", OrderRequest(item, 1)).orderId)
      val stateStore = summon[DaprCapability].state(OrderServiceApp.StateName)
      ids.zip(items).foreach { (id, item) =>
        assertEquals(stateStore.get[OrderRequest](StateStoreKey(id)).map(_.item), Some(item))
      }
    }

  test("order service: all invoke routes are declared in the app"):
    withService(OrderServiceApp()) {
      val app = OrderServiceApp()
      assert(app.invokeRoutes.exists(_.methodName.value == "place-order"))
      assert(app.invokeRoutes.exists(_.methodName.value == "get-order"))
      assert(app.invokeRoutes.exists(_.methodName.value == "query-orders"))
    }
