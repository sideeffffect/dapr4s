package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[OrderServiceApp]] against a real Dapr sidecar, dispatched through a real
  * [[dapr4s.internal.DaprAppServer]] HTTP server.
  *
  * Each test starts a real HTTP server wrapping the handler app and exercises it via HTTP POST — the full encode → HTTP
  * → decode path is tested, with state persisted in the real in-memory Dapr sidecar.
  */
@scala.caps.assumeSafe
class OrderServiceIntegrationTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("order-service-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("pubsub", "pubsub.in-memory", "v1", Collections.emptyMap())),
    )
    c.start()
    c

  // -------------------------------------------------------------------------
  // Tests
  // -------------------------------------------------------------------------

  test("order service: place-order saves order to state store"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)
        withServer(app) { port =>
          val req = OrderRequest("laptop", 2)
          val resp = invokeMethod[OrderRequest, OrderResponse](port, "place-order", req)

          assertEquals(resp.status, "accepted")
          assert(resp.orderId.nonEmpty)

          val saved = scope
            .state(StoreName("statestore"))
            .get[OrderRequest](StateKey(resp.orderId))
          assertEquals(saved, Some(req))
        }
    }

  test("order service: get-order retrieves a previously placed order"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)
        withServer(app) { port =>
          val req = OrderRequest("keyboard", 1)
          val resp = invokeMethod[OrderRequest, OrderResponse](port, "place-order", req)

          val fetched = invokeMethod[String, Option[OrderRequest]](port, "get-order", resp.orderId)
          assertEquals(fetched, Some(req))
        }
    }

  test("order service: get-order returns None for unknown order ID"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)
        withServer(app) { port =>
          val fetched = invokeMethod[String, Option[OrderRequest]](port, "get-order", "no-such-order-id")
          assertEquals(fetched, None)
        }
    }

  test("order service: two consecutive orders get distinct IDs"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)
        withServer(app) { port =>
          val r1 = invokeMethod[OrderRequest, OrderResponse](port, "place-order", OrderRequest("mouse", 1))
          val r2 = invokeMethod[OrderRequest, OrderResponse](port, "place-order", OrderRequest("monitor", 2))

          assertNotEquals(r1.orderId, r2.orderId)
          assertEquals(r1.status, "accepted")
          assertEquals(r2.status, "accepted")
        }
    }

  test("order service: publish does not throw (fire-and-forget)"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)
        withServer(app) { port =>
          val resp = invokeMethod[OrderRequest, OrderResponse](port, "place-order", OrderRequest("headphones", 3))
          assertEquals(resp.status, "accepted")
        }
    }

  test("order service: bulk orders all land in state store"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)
        withServer(app) { port =>
          val items = List("pencil", "ruler", "eraser", "notebook", "stapler")
          val ids = items.map { item =>
            val resp = invokeMethod[OrderRequest, OrderResponse](port, "place-order", OrderRequest(item, 1))
            resp.orderId
          }

          val stateStore = scope.state(StoreName("statestore"))
          ids.zip(items).foreach { (id, item) =>
            val saved = stateStore.get[OrderRequest](StateKey(id))
            assertEquals(saved.map(_.item), Some(item))
          }
        }
    }

  test("order service: all routes are declared in the app"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)

        assert(app.invocations.exists(_.methodName.value == "place-order"))
        assert(app.invocations.exists(_.methodName.value == "get-order"))
        assert(app.invocations.exists(_.methodName.value == "query-orders"))
    }
