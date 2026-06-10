//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.GenericContainer
import com.dimafeng.testcontainers.lifecycle.and
import com.dimafeng.testcontainers.lifecycle.Andable.AndableOps
import com.dimafeng.testcontainers.munit.TestContainersForAll
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import munit.FunSuite
import unsafeExceptions.canThrowAny
import scala.concurrent.duration.DurationInt

/** Tests for every [[LockCapability]] method through real [[dapr4s.internal.DaprAppServer]] HTTP dispatch, backed by a
  * real `lock.redis` component via Testcontainers.
  *
  * Each test uses a unique resource ID (UUID) so tests sharing the same Dapr sidecar container do not interfere.
  */
@scala.caps.assumeSafe
class LockCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

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
        .withAppName("lock-server-test")
        .withAppPort(0)
        .withComponent(Component("lockstore", "lock.redis", "v1", java.util.Map.of("redisHost", "redis:6379")))
        .dependsOn(redis.container),
    )
    c.start()
    redis and c

  private def uniqueResource() = LockResourceId(s"res-${java.util.UUID.randomUUID()}")
  private def uniqueOwner() = LockOwner(s"owner-${java.util.UUID.randomUUID()}")

  // ---- tryLock ---------------------------------------------------------------

  test("lock: tryLock on free resource returns true"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val res = uniqueResource()
        val own = uniqueOwner()
        DaprCapability.lock(LockStoreName("lockstore")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[Unit, Boolean](InvokeMethodName("lock")) { _ =>
                  try LockCapability.tryLock(res, own, 30.seconds)
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val result = JsonCodec.decodeOrThrow[Boolean](httpPost(s"http://localhost:$port/lock", "null"))
            assert(result, "Expected tryLock to succeed on free resource")
          }
        }
    }

  test("lock: tryLock on held resource returns false"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val res = uniqueResource()
        DaprCapability.lock(LockStoreName("lockstore")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[String, Boolean](InvokeMethodName("lock")) { ownerStr =>
                  try LockCapability.tryLock(res, LockOwner(ownerStr), 30.seconds)
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val r1 = JsonCodec.decodeOrThrow[Boolean](httpPost(s"http://localhost:$port/lock", "\"owner-1\""))
            val r2 = JsonCodec.decodeOrThrow[Boolean](httpPost(s"http://localhost:$port/lock", "\"owner-2\""))
            assert(r1, "First tryLock should succeed")
            assert(!r2, "Second tryLock should fail — resource already held")
          }
        }
    }

  // ---- unlock ----------------------------------------------------------------

  test("lock: unlock by correct owner returns Success"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val res = uniqueResource()
        val own = uniqueOwner()
        DaprCapability.lock(LockStoreName("lockstore")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[Unit, Boolean](InvokeMethodName("acquire")) { _ =>
                  try LockCapability.tryLock(res, own, 30.seconds)
                  catch case e: Exception => throw e
                },
                InvokeRoute[Unit, String](InvokeMethodName("release")) { _ =>
                  try LockCapability.unlock(res, own).toString
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/acquire", "null")
            val status = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/release", "null"))
            assertEquals(status, "Success")
          }
        }
    }

  test("lock: unlock on non-existent lock returns LockNotFound"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val res = uniqueResource()
        val own = uniqueOwner()
        DaprCapability.lock(LockStoreName("lockstore")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[Unit, String](InvokeMethodName("release")) { _ =>
                  try LockCapability.unlock(res, own).toString
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val status = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/release", "null"))
            assertEquals(status, "LockNotFound")
          }
        }
    }

  test("lock: unlock by wrong owner returns InternalError"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val res = uniqueResource()
        val realOwner = uniqueOwner()
        DaprCapability.lock(LockStoreName("lockstore")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[Unit, Boolean](InvokeMethodName("acquire")) { _ =>
                  try LockCapability.tryLock(res, realOwner, 30.seconds)
                  catch case e: Exception => throw e
                },
                InvokeRoute[Unit, String](InvokeMethodName("release-wrong")) { _ =>
                  try LockCapability.unlock(res, LockOwner("intruder")).toString
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            httpPost(s"http://localhost:$port/acquire", "null")
            val status = JsonCodec.decodeOrThrow[String](httpPost(s"http://localhost:$port/release-wrong", "null"))
            assertEquals(status, "InternalError")
          }
        }
    }

  // ---- re-lock after unlock --------------------------------------------------

  test("lock: re-lock after successful unlock succeeds"):
    withContainers { case _ and c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val res = uniqueResource()
        val own = uniqueOwner()
        DaprCapability.lock(LockStoreName("lockstore")) {
          withServer(
            DaprApp(invokeRoutes =
              List(
                InvokeRoute[Unit, Boolean](InvokeMethodName("lock")) { _ =>
                  try LockCapability.tryLock(res, own, 30.seconds)
                  catch case e: Exception => throw e
                },
                InvokeRoute[Unit, String](InvokeMethodName("unlock")) { _ =>
                  try LockCapability.unlock(res, own).toString
                  catch case e: Exception => throw e
                },
              ),
            ),
          ) { port =>
            val r1 = JsonCodec.decodeOrThrow[Boolean](httpPost(s"http://localhost:$port/lock", "null"))
            assert(r1)
            httpPost(s"http://localhost:$port/unlock", "null")
            val r2 = JsonCodec.decodeOrThrow[Boolean](httpPost(s"http://localhost:$port/lock", "null"))
            assert(r2, "Should be able to acquire lock again after releasing it")
          }
        }
    }
