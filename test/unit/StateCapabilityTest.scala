package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite

// NOTE (Issue 27): Despite its name, StateCapabilityTest covers the full mock-based
// test suite including State, PubSub, Secrets, Configuration, and DaprRuntime.run.
// The class is not renamed to avoid disrupting test discovery and history.
class StateCapabilityTest extends FunSuite:

  /** Helper: run a block against a fresh [[MockDaprScope]]. */
  def withScope[T](body: DaprScope ?=> T): T =
    val scope = MockDaprScope()
    body(using scope)

  // -------------------------------------------------------------------------
  // get / save
  // -------------------------------------------------------------------------

  test("save then get returns the saved value"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("key1", "hello")
      assertEquals(state.get[String]("key1"), Some("hello"))

  test("get on missing key returns None"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      assertEquals(state.get[String]("missing"), None)

  test("save overwrites previous value"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "first")
      state.save("k", "second")
      assertEquals(state.get[String]("k"), Some("second"))

  test("save and get Int"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("n", 42)
      assertEquals(state.get[Int]("n"), Some(42))

  // -------------------------------------------------------------------------
  // getWithETag
  // -------------------------------------------------------------------------

  test("getWithETag returns value and etag after save"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v")
      val entry = state.getWithETag[String]("k")
      assertEquals(entry.value, Some("v"))
      assert(entry.etag.isDefined)

  test("getWithETag on missing key returns empty entry"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      val entry = state.getWithETag[String]("absent")
      assertEquals(entry.value, None)
      assertEquals(entry.etag, None)

  // -------------------------------------------------------------------------
  // saveWithETag
  // -------------------------------------------------------------------------

  test("saveWithETag succeeds with correct etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v1")
      val entry = state.getWithETag[String]("k")
      val etag  = entry.etag.get
      state.saveWithETag("k", "v2", etag)
      assertEquals(state.get[String]("k"), Some("v2"))

  test("saveWithETag throws ETagMismatchException on wrong etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v1")
      intercept[ETagMismatchException]:
        state.saveWithETag("k", "v2", ETag("wrong-etag"))

  test("saveWithETag throws ETagMismatchException when key does not exist"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      intercept[ETagMismatchException]:
        state.saveWithETag("nonexistent", "v", ETag("any-etag"))

  // -------------------------------------------------------------------------
  // delete
  // -------------------------------------------------------------------------

  test("delete removes a key"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v")
      state.delete("k")
      assertEquals(state.get[String]("k"), None)

  test("delete on absent key is a no-op"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.delete("nonexistent") // must not throw

  // -------------------------------------------------------------------------
  // deleteWithETag
  // -------------------------------------------------------------------------

  test("deleteWithETag succeeds with correct etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v")
      val etag = state.getWithETag[String]("k").etag.get
      state.deleteWithETag("k", etag)
      assertEquals(state.get[String]("k"), None)

  test("deleteWithETag throws ETagMismatchException on wrong etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v")
      intercept[ETagMismatchException]:
        state.deleteWithETag("k", ETag("wrong"))

  // -------------------------------------------------------------------------
  // transaction
  // -------------------------------------------------------------------------

  test("transaction upsert inserts new key"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.transaction(Seq(StateOp.UpsertOp[String]("newKey", "newVal")))
      assert(state.get[String]("newKey").isDefined)

  test("transaction delete removes existing key"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v")
      state.transaction(Seq(StateOp.DeleteOp("k")))
      assertEquals(state.get[String]("k"), None)

  // -------------------------------------------------------------------------
  // PubSub through mock scope
  // -------------------------------------------------------------------------

  test("publish records event in mock scope"):
    val scope = MockDaprScope()
    val pubsub = scope.pubsub(PubSubName("my-pubsub"))
    pubsub.publish(Topic("orders"), "order-payload")
    val events = scope.publishedEvents
    assertEquals(events.length, 1)
    assertEquals(events.head._1, "my-pubsub")
    assertEquals(events.head._2, "orders")
    assertEquals(events.head._4, Map.empty[String, String])

  test("publishWithMetadata records event with metadata in mock scope"):
    val scope = MockDaprScope()
    val pubsub = scope.pubsub(PubSubName("my-pubsub"))
    pubsub.publishWithMetadata(Topic("orders"), "payload", Map("k" -> "v"))
    val events = scope.publishedEvents
    assertEquals(events.length, 1)
    assertEquals(events.head._4, Map("k" -> "v"))

  // -------------------------------------------------------------------------
  // Secrets through mock scope
  // -------------------------------------------------------------------------

  test("secrets get returns seeded value"):
    val scope = MockDaprScope()
    scope.seedSecret("vault", "db-password", "s3cr3t")
    val secrets = scope.secrets(SecretStoreName("vault"))
    assertEquals(secrets.get("db-password"), "s3cr3t")

  test("secrets get throws DaprException for missing key"):
    val scope = MockDaprScope()
    val secrets = scope.secrets(SecretStoreName("vault"))
    intercept[DaprException]:
      secrets.get("nonexistent")

  test("secrets getBulk returns all seeded values"):
    val scope = MockDaprScope()
    scope.seedSecret("vault", "a", "1")
    scope.seedSecret("vault", "b", "2")
    val secrets = scope.secrets(SecretStoreName("vault"))
    assertEquals(secrets.getBulk(), Map("a" -> "1", "b" -> "2"))

  // -------------------------------------------------------------------------
  // Configuration through mock scope
  // -------------------------------------------------------------------------

  test("config get returns seeded items"):
    val scope = MockDaprScope()
    scope.seedConfig("app-config", "log-level", ConfigItem("log-level", "INFO", "1"))
    val config = scope.config(ConfigStoreName("app-config"))
    val result = config.get("log-level")
    assertEquals(result("log-level").value, "INFO")

  test("config get returns empty map for unknown keys"):
    val scope = MockDaprScope()
    val config = scope.config(ConfigStoreName("app-config"))
    assert(config.get("unknown").isEmpty)

  // -------------------------------------------------------------------------
  // DaprRuntime.run (using mock scope indirectly via withScope helper)
  // -------------------------------------------------------------------------

  test("DaprScope factory is available as context parameter"):
    withScope:
      val scope = summon[DaprScope]
      val state = scope.state(StoreName("s"))
      state.save("x", 1)
      assertEquals(state.get[Int]("x"), Some(1))

  // -------------------------------------------------------------------------
  // Closed capability rejection (ClosedCapabilityRejection invariant)
  // -------------------------------------------------------------------------

  test("state operation throws IllegalStateException after scope close"):
    val scope = MockDaprScope()
    val state = scope.state(StoreName("test-store"))
    state.save("k", "v")
    scope.close()
    intercept[IllegalStateException]:
      state.get[String]("k")

  test("scope factory throws IllegalStateException after close"):
    val scope = MockDaprScope()
    scope.close()
    intercept[IllegalStateException]:
      scope.state(StoreName("test-store"))

  test("pubsub operation throws IllegalStateException after scope close"):
    val scope  = MockDaprScope()
    val pubsub = scope.pubsub(PubSubName("ps"))
    scope.close()
    intercept[IllegalStateException]:
      pubsub.publish(Topic("t"), "msg")

  test("secrets operation throws IllegalStateException after scope close"):
    val scope   = MockDaprScope()
    scope.seedSecret("vault", "k", "v")
    val secrets = scope.secrets(SecretStoreName("vault"))
    scope.close()
    intercept[IllegalStateException]:
      secrets.get("k")
