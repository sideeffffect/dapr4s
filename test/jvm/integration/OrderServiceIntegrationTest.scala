//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.DaprContainer
import munit.FunSuite
import org.testcontainers.containers.Network

/** Integration tests for [[OrderServiceApp]] against a real Dapr sidecar, dispatched through a real
  * [[dapr4s.internal.DaprAppServer]] HTTP server.
  *
  * Each test starts a real HTTP server wrapping the handler app and exercises it via HTTP POST — the full encode → HTTP
  * → decode path is tested, with state persisted in the real `state.redis` Dapr sidecar (the shared
  * scripts/it/components set — see [[RedisFixture]]).
  */
@scala.caps.assumeSafe
class OrderServiceIntegrationTest extends FunSuite, RedisFixture, DaprServerTestBase:

  override type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val network = Network.newNetwork()
    val res = startRedis(network)
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("order-service-test")
        .withAppPort(0)
        .withComponent(res.component("statestore"))
        .withComponent(res.component("pubsub")),
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
            .state(StateStoreName("statestore"))
            .get[OrderRequest](StateStoreKey(resp.orderId))
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

          val stateStore = scope.state(StateStoreName("statestore"))
          ids.zip(items).foreach { (id, item) =>
            val saved = stateStore.get[OrderRequest](StateStoreKey(id))
            assertEquals(saved.map(_.item), Some(item))
          }
        }
    }

  test("order service: all routes are declared in the app"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceApp()(using scope)

        assert(app.invokeRoutes.exists(_.methodName.value == "place-order"))
        assert(app.invokeRoutes.exists(_.methodName.value == "get-order"))
        assert(app.invokeRoutes.exists(_.methodName.value == "query-orders"))
    }
