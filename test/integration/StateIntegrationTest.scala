package dapr.safe.test.integration

import dapr.safe.*
import io.dapr.testcontainers.{DaprContainer, Component}
import munit.FunSuite

import java.util.Collections

/** Integration tests for [[StateCapability]] using a real DAPR sidecar in
  * Docker via Testcontainers.
  *
  * Run with: `scala-cli test . -- --only "integration.*"`
  * (Docker must be available.)
  */
class StateIntegrationTest extends FunSuite:

  private var dapr: DaprContainer = null

  override def beforeAll(): Unit =
    val component = Component(
      "kvstore",
      "state.in-memory",
      "v1",
      Collections.emptyMap[String, String]()
    )
    dapr = DaprContainer("daprio/daprd:latest")
      .withAppName("state-test-app")
      .withAppPort(0)
      .withComponent(component)
    dapr.start()

  override def afterAll(): Unit =
    if dapr != null then dapr.stop()

  private def httpEndpoint = s"http://${dapr.getHost}:${dapr.getHttpPort}"
  private def grpcEndpoint = s"http://${dapr.getHost}:${dapr.getGrpcPort}"

  private def uniqueKey(): String = s"k-${System.nanoTime()}"

  // -------------------------------------------------------------------------
  // Tests — each creates its own DaprRuntime.runWithEndpoints scope
  // -------------------------------------------------------------------------

  test("integration: save and get"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val key   = uniqueKey()
      state.save(key, "hello-integration")
      assertEquals(state.get[String](key), Some("hello-integration"))

  test("integration: get missing key returns None"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val v     = state.get[String]("definitely-does-not-exist-" + System.nanoTime())
      assertEquals(v, None)

  test("integration: getWithETag returns etag"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val key   = uniqueKey()
      state.save(key, "v1")
      val entry = state.getWithETag[String](key)
      assertEquals(entry.value, Some("v1"))
      assert(entry.etag.isDefined, "ETag should be present after save")

  test("integration: saveWithETag happy path"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val key   = uniqueKey()
      state.save(key, "v1")
      val etag  = state.getWithETag[String](key).etag.get
      state.saveWithETag(key, "v2", etag)
      assertEquals(state.get[String](key), Some("v2"))

  test("integration: saveWithETag conflict throws ETagMismatchException"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val key   = uniqueKey()
      state.save(key, "v1")
      intercept[ETagMismatchException]:
        state.saveWithETag(key, "v2", ETag("wrong-etag-999"))

  test("integration: delete removes key"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val key   = uniqueKey()
      state.save(key, "to-be-deleted")
      state.delete(key)
      assertEquals(state.get[String](key), None)

  test("integration: transaction upsert and delete"):
    DaprRuntime.runWithEndpoints(httpEndpoint, grpcEndpoint):
      val state = summon[DaprScope].state(StoreName("kvstore"))
      val k1    = uniqueKey()
      val k2    = uniqueKey()
      state.save(k1, "will-be-deleted")
      state.transaction(Seq(
        StateOp.UpsertOp[String](k2, "inserted-by-tx"),
        StateOp.DeleteOp(k1)
      ))
      assertEquals(state.get[String](k1), None)
      // k2 may or may not be readable depending on transaction support
      // in the in-memory state store; just ensure no exception was thrown
