package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.lifecycle.Andable.AndableOps
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import munit.FunSuite

import java.util.Collections

/** End-to-end integration test that runs both [[OrderServiceApp]] and [[InventoryServiceApp]] against the same real
  * Dapr sidecar, exercising the full order-placement → inventory-update flow.
  *
  * Each service runs in its own [[dapr4s.internal.DaprAppServer]] HTTP server. Order-service pub/sub delivery to the
  * inventory service is simulated by POSTing a CloudEvent JSON envelope directly to the inventory server's subscription
  * route — the same format Dapr uses in production.
  *
  * Showcases how multiple dapr4s capabilities work together:
  *   - [[StateCapability]] — persisting orders and stock levels
  *   - [[PubSubCapability]] — publishing order events (fire-and-forget to real Dapr sidecar)
  *   - [[DistributedLockCapability]] — serialising concurrent stock updates
  *   - [[DaprApp]] — declarative handler composition
  */
@scala.caps.assumeSafe
class EndToEndIntegrationTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

  type Containers = GenericContainer and DaprTestContainer

  override def startContainers(): GenericContainer and DaprTestContainer =
    val network = Network.newNetwork()

    val redis = GenericContainer(
      dockerImage = "redis:7-alpine",
      exposedPorts = Seq(6379),
      waitStrategy = Wait.forLogMessage(".*Ready to accept connections.*", 1),
    )
    redis.container.withNetwork(network)
    redis.container.withNetworkAliases("redis")
    redis.start()

    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(network)
        .withAppName("e2e-test")
        .withAppPort(0)
        .withComponent(Component("statestore", "state.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("pubsub", "pubsub.in-memory", "v1", Collections.emptyMap()))
        .withComponent(Component("lockstore", "lock.redis", "v1", java.util.Map.of("redisHost", "redis:6379")))
        .dependsOn(redis.container),
    )
    c.start()
    redis and c

  // -------------------------------------------------------------------------

  test("e2e: placing an order decrements inventory stock"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceApp()(using scope)
        val inventoryApp = InventoryServiceApp()(using scope)

        withServer(orderApp) { orderPort =>
          withServer(inventoryApp) { invPort =>
            invokeMethod[StockLevel, StockLevel](invPort, "seed-stock", StockLevel("tablet", 30))

            val req = OrderRequest("tablet", 4)
            val resp = invokeMethod[OrderRequest, OrderResponse](orderPort, "place-order", req)

            assertEquals(resp.status, "accepted")
            assert(resp.orderId.nonEmpty, "orderId must be non-empty")

            val savedOrder = scope
              .state(StoreName("statestore"))
              .get[OrderRequest](StateKey(resp.orderId))
            assertEquals(savedOrder, Some(req))

            // Simulate Dapr delivering the published event to the inventory service
            deliverCloudEvent(invPort, "orders", "pubsub", OrderEvent(resp.orderId, "tablet", 4))

            val stock = invokeMethod[String, StockLevel](invPort, "get-stock", "tablet")
            assertEquals(stock.available, 26)
          }
        }
    }

  test("e2e: multiple orders reduce inventory cumulatively"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceApp()(using scope)
        val inventoryApp = InventoryServiceApp()(using scope)

        withServer(orderApp) { orderPort =>
          withServer(inventoryApp) { invPort =>
            invokeMethod[StockLevel, StockLevel](invPort, "seed-stock", StockLevel("cable", 100))

            val quantities = List(5, 10, 3, 7)
            quantities.foreach { qty =>
              val resp = invokeMethod[OrderRequest, OrderResponse](orderPort, "place-order", OrderRequest("cable", qty))
              deliverCloudEvent(invPort, "orders", "pubsub", OrderEvent(resp.orderId, "cable", qty))
            }

            val stock = invokeMethod[String, StockLevel](invPort, "get-stock", "cable")
            assertEquals(stock.available, 75)
          }
        }
    }

  test("e2e: orders for different items tracked independently"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceApp()(using scope)
        val inventoryApp = InventoryServiceApp()(using scope)

        withServer(orderApp) { orderPort =>
          withServer(inventoryApp) { invPort =>
            invokeMethod[StockLevel, StockLevel](invPort, "seed-stock", StockLevel("pen", 50))
            invokeMethod[StockLevel, StockLevel](invPort, "seed-stock", StockLevel("pencil", 80))

            val penResp = invokeMethod[OrderRequest, OrderResponse](orderPort, "place-order", OrderRequest("pen", 6))
            val pencilResp =
              invokeMethod[OrderRequest, OrderResponse](orderPort, "place-order", OrderRequest("pencil", 4))

            deliverCloudEvent(invPort, "orders", "pubsub", OrderEvent(penResp.orderId, "pen", 6))
            deliverCloudEvent(invPort, "orders", "pubsub", OrderEvent(pencilResp.orderId, "pencil", 4))

            assertEquals(invokeMethod[String, StockLevel](invPort, "get-stock", "pen").available, 44)
            assertEquals(invokeMethod[String, StockLevel](invPort, "get-stock", "pencil").available, 76)
          }
        }
    }

  test("e2e: order state survives re-query after inventory update"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceApp()(using scope)
        val inventoryApp = InventoryServiceApp()(using scope)

        withServer(orderApp) { orderPort =>
          withServer(inventoryApp) { invPort =>
            invokeMethod[StockLevel, StockLevel](invPort, "seed-stock", StockLevel("drive", 10))

            val req = OrderRequest("drive", 2)
            val resp = invokeMethod[OrderRequest, OrderResponse](orderPort, "place-order", req)
            deliverCloudEvent(invPort, "orders", "pubsub", OrderEvent(resp.orderId, "drive", 2))

            val fetched = invokeMethod[String, Option[OrderRequest]](orderPort, "get-order", resp.orderId)
            assertEquals(fetched, Some(req))

            assertEquals(invokeMethod[String, StockLevel](invPort, "get-stock", "drive").available, 8)
          }
        }
    }

  test("e2e: concurrent orders use bulk state operations"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceApp()(using scope)
        val inventoryApp = InventoryServiceApp()(using scope)

        withServer(orderApp) { orderPort =>
          withServer(inventoryApp) { invPort =>
            invokeMethod[StockLevel, StockLevel](invPort, "seed-stock", StockLevel("lamp", 200))

            val resps = (1 to 10).map { i =>
              invokeMethod[OrderRequest, OrderResponse](orderPort, "place-order", OrderRequest("lamp", i))
            }.toList

            assertEquals(resps.map(_.orderId).distinct.size, 10)

            resps.zipWithIndex.foreach { (resp, i) =>
              deliverCloudEvent(invPort, "orders", "pubsub", OrderEvent(resp.orderId, "lamp", i + 1))
            }

            val stock = invokeMethod[String, StockLevel](invPort, "get-stock", "lamp")
            assertEquals(stock.available, 145)
          }
        }
    }

  test("e2e: DaprCapability is closed after run block — capabilities become unavailable"):
    withContainers { case _ and c =>
      var capturedScope: AnyRef | Null = null

      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        capturedScope = summon[DaprCapability].asInstanceOf[AnyRef]

      val closed = capturedScope.asInstanceOf[DaprCapability | Null]
      if closed != null then
        intercept[Exception]:
          closed.state(StoreName("statestore")).get[String](StateKey("k"))
    }

  test("e2e: DaprApp composition with ++ merges routes"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val scope = summon[DaprCapability]
        val orderApp = OrderServiceApp()(using scope)
        val inventoryApp = InventoryServiceApp()(using scope)
        val combined = orderApp ++ inventoryApp

        assert(combined.invocations.exists(_.methodName.value == "place-order"))
        assert(combined.invocations.exists(_.methodName.value == "get-stock"))
        assert(combined.subscriptions.exists(_.topic.value == "orders"))
    }
