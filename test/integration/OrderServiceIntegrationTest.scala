package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.test.integration.apps.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[OrderServiceHandlers]] against a real Dapr sidecar.
  *
  * Uses Testcontainers to spin up a Dapr sidecar with an in-memory state store and in-memory pub/sub. The [[DaprApp]]
  * returned by `daprApp()` is exercised via [[TestDaprApp.call]] — no HTTP round-trip required.
  *
  * Run with: `scala-cli test . -- --only "integration.*Order*"` (Docker must be available.)
  */
@scala.caps.assumeSafe
class OrderServiceIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val c = DaprTestContainer(
      DaprContainer("daprio/daprd:1.17.0")
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
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)

        val req = OrderRequest("laptop", 2)
        val resp = TestDaprApp.call[OrderRequest](app, "place-order", req)[OrderResponse]

        assertEquals(resp.status, "accepted")
        assert(resp.orderId.nonEmpty)

        // Order is persisted in the state store
        val saved = scope
          .state(StoreName("statestore"))
          .get[OrderRequest](StateKey(resp.orderId))
        assertEquals(saved, Some(req))
    }

  test("order service: get-order retrieves a previously placed order"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)

        val req = OrderRequest("keyboard", 1)
        val resp = TestDaprApp.call[OrderRequest](app, "place-order", req)[OrderResponse]

        val fetched = TestDaprApp.call[String](app, "get-order", resp.orderId)[Option[OrderRequest]]
        assertEquals(fetched, Some(req))
    }

  test("order service: get-order returns None for unknown order ID"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)
        val fetched = TestDaprApp.call[String](app, "get-order", "no-such-order-id")[Option[OrderRequest]]
        assertEquals(fetched, None)
    }

  test("order service: two consecutive orders get distinct IDs"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)

        val r1 = TestDaprApp.call[OrderRequest](app, "place-order", OrderRequest("mouse", 1))[OrderResponse]
        val r2 = TestDaprApp.call[OrderRequest](app, "place-order", OrderRequest("monitor", 2))[OrderResponse]

        assertNotEquals(r1.orderId, r2.orderId)
        assertEquals(r1.status, "accepted")
        assertEquals(r2.status, "accepted")
    }

  test("order service: publish does not throw (fire-and-forget)"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)

        // place-order publishes an event internally — if that throws, the test fails
        val resp = TestDaprApp.call[OrderRequest](app, "place-order", OrderRequest("headphones", 3))[OrderResponse]
        assertEquals(resp.status, "accepted")
    }

  test("order service: bulk orders all land in state store"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)

        val items = List("pencil", "ruler", "eraser", "notebook", "stapler")
        val ids = items.map { item =>
          val resp = TestDaprApp.call[OrderRequest](app, "place-order", OrderRequest(item, 1))[OrderResponse]
          resp.orderId
        }

        val stateStore = scope.state(StoreName("statestore"))
        ids.zip(items).foreach { (id, item) =>
          val saved = stateStore.get[OrderRequest](StateKey(id))
          assertEquals(saved.map(_.item), Some(item))
        }
    }

  test("order service: all routes are declared in the app"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val app = OrderServiceHandlers.daprApp()(using scope)

        assert(app.invocations.exists(_.methodName.value == "place-order"))
        assert(app.invocations.exists(_.methodName.value == "get-order"))
        assert(app.invocations.exists(_.methodName.value == "query-orders"))
    }
