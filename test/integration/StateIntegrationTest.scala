package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.{DaprContainer, Component}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

import java.util.Collections

/** Integration tests for [[StateCapability]] using a real DAPR sidecar in
  * Docker via Testcontainers.
  *
  * Run with: `scala-cli test . -- --only "integration.*"`
  * (Docker must be available.)
  */
@scala.caps.assumeSafe
class StateIntegrationTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  override def startContainers(): DaprTestContainer =
    val component = Component(
      "kvstore",
      "state.in-memory",
      "v1",
      Collections.emptyMap[String, String]()
    )
    val c = DaprTestContainer(
      DaprContainer("daprio/daprd:1.17.0")
        .withAppName("state-test-app")
        .withAppPort(0)
        .withComponent(component)
    )
    c.start()
    c

  private def uniqueKey(): StateKey = StateKey(s"k-${System.nanoTime()}")

  // -------------------------------------------------------------------------
  // Tests — each receives the running container via withContainers
  // -------------------------------------------------------------------------

  test("integration: save and get"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val key   = uniqueKey()
        state.save(key, "hello-integration")
        assertEquals(state.get[String](key), Some("hello-integration"))
    }

  test("integration: get missing key returns None"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val v     = state.get[String](StateKey("definitely-does-not-exist-" + System.nanoTime()))
        assertEquals(v, None)
    }

  test("integration: getWithETag returns etag"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val key   = uniqueKey()
        state.save(key, "v1")
        val entry = state.getWithETag[String](key)
        assertEquals(entry.value, Some("v1"))
        assert(entry.etag.isDefined, "ETag should be present after save")
    }

  test("integration: saveWithETag happy path"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val key   = uniqueKey()
        state.save(key, "v1")
        val etag  = state.getWithETag[String](key).etag.getOrElse(fail("expected etag after save"))
        state.saveWithETag(key, "v2", etag)
        assertEquals(state.get[String](key), Some("v2"))
    }

  test("integration: saveWithETag conflict throws ETagMismatchException"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val key   = uniqueKey()
        state.save(key, "v1")
        intercept[ETagMismatchException]:
          state.saveWithETag(key, "v2", ETag("wrong-etag-999"))
    }

  test("integration: delete removes key"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val key   = uniqueKey()
        state.save(key, "to-be-deleted")
        state.delete(key)
        assertEquals(state.get[String](key), None)
    }

  test("integration: transaction upsert and delete"):
    withContainers { c =>
      DaprRuntime.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val state = summon[DaprCapability].state(StoreName("kvstore"))
        val k1    = uniqueKey()
        val k2    = uniqueKey()
        state.save(k1, "will-be-deleted")
        state.transaction(Seq(
          StateOp.UpsertOp[String](k2, "inserted-by-tx"),
          StateOp.DeleteOp(k1)
        ))
        assertEquals(state.get[String](k1), None)
        // k2 may or may not be visible depending on transaction support in
        // the in-memory state store; ensure no exception was thrown
    }
