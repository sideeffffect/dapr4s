package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite
import language.experimental.saferExceptions

// NOTE: Despite its name, StateCapabilityTest covers the full mock-based test suite
// including State, PubSub, Secrets, Configuration, Lock, and closed-scope invariants.
@scala.caps.assumeSafe
class StateCapabilityTest extends FunSuite:

  /** Provide CanThrow[Exception] via a method body so no lambda ever captures canThrowAny.
    * Multiple calls from different lambdas are safe — each creates a fresh method stack frame.
    */
  def runSafe[T](body: CanThrow[Exception] ?=> T): T =
    given CanThrow[Exception] = unsafeExceptions.canThrowAny
    body

  /** Run a block against a fresh [[MockDaprScope]].
    * Uses runSafe internally so canThrowAny is never captured in any lambda.
    */
  def withScope[T](body: (DaprScope, CanThrow[Exception]) ?=> T): T =
    given scope: DaprScope = MockDaprScope()
    runSafe:
      body

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
  // getBulk / saveBulk
  // -------------------------------------------------------------------------

  test("saveBulk then getBulk returns all values"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.saveBulk[Int](Seq("a" -> 1, "b" -> 2, "c" -> 3))
      val results = state.getBulk[Int](Seq("a", "b", "c"))
      assertEquals(results("a").value, Some(1))
      assertEquals(results("b").value, Some(2))
      assertEquals(results("c").value, Some(3))

  test("getBulk returns missing key as None value"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("exists", "v")
      val results = state.getBulk[String](Seq("exists", "missing"))
      assertEquals(results("exists").value, Some("v"))
      assertEquals(results("missing").value, None)

  // -------------------------------------------------------------------------
  // saveWithETag
  // -------------------------------------------------------------------------

  test("saveWithETag succeeds with correct etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v1")
      val entry = state.getWithETag[String]("k")
      val etag  = entry.etag.getOrElse(fail("expected etag after save"))
      state.saveWithETag("k", "v2", etag)
      assertEquals(state.get[String]("k"), Some("v2"))

  test("saveWithETag throws ETagMismatchException on wrong etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v1")
      var exOpt: Exception | Null = null
      try state.saveWithETag("k", "v2", ETag("wrong-etag"))
      catch case e: ETagMismatchException => exOpt = e
      assert(exOpt != null)

  test("saveWithETag throws ETagMismatchException when key does not exist"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      var exOpt: Exception | Null = null
      try state.saveWithETag("nonexistent", "v", ETag("any-etag"))
      catch case e: ETagMismatchException => exOpt = e
      assert(exOpt != null)

  test("ETagMismatchException is a DaprStateException"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v1")
      var exOpt: Exception | Null = null
      try state.saveWithETag("k", "v2", ETag("wrong-etag"))
      catch case e: DaprStateException => exOpt = e
      assert(exOpt != null && exOpt.isInstanceOf[ETagMismatchException])

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
      val etag = state.getWithETag[String]("k").etag.getOrElse(fail("expected etag after save"))
      state.deleteWithETag("k", etag)
      assertEquals(state.get[String]("k"), None)

  test("deleteWithETag throws ETagMismatchException on wrong etag"):
    withScope:
      val state = summon[DaprScope].state(StoreName("test-store"))
      state.save("k", "v")
      var exOpt: Exception | Null = null
      try state.deleteWithETag("k", ETag("wrong"))
      catch case e: ETagMismatchException => exOpt = e
      assert(exOpt != null)

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
    runSafe:
      val scope = MockDaprScope()
      val pubsub = scope.pubsub(PubSubName("my-pubsub"))
      pubsub.publish(Topic("orders"), "order-payload")
      val events = scope.publishedEvents
      assertEquals(events.length, 1)
      val (psName, topicName, _, meta) = events(0)
      assertEquals(psName, "my-pubsub")
      assertEquals(topicName, "orders")
      assertEquals(meta, Map.empty[String, String])

  test("publishWithMetadata records event with metadata in mock scope"):
    runSafe:
      val scope = MockDaprScope()
      val pubsub = scope.pubsub(PubSubName("my-pubsub"))
      pubsub.publishWithMetadata(Topic("orders"), "payload", Map("k" -> "v"))
      val events = scope.publishedEvents
      assertEquals(events.length, 1)
      val (_, _, _, meta) = events(0)
      assertEquals(meta, Map("k" -> "v"))

  test("bulkPublish records all entries in mock scope"):
    runSafe:
      val scope = MockDaprScope()
      val pubsub = scope.pubsub(PubSubName("my-pubsub"))
      val entries = Seq(
        BulkPublishEntry("1", "event-a"),
        BulkPublishEntry("2", "event-b")
      )
      val result = pubsub.bulkPublish(Topic("orders"), entries)
      assertEquals(scope.publishedEvents.length, 2)
      assertEquals(result.failedEntries, List.empty)

  // -------------------------------------------------------------------------
  // Secrets through mock scope
  // -------------------------------------------------------------------------

  test("secrets get returns seeded value"):
    runSafe:
      val scope = MockDaprScope()
      scope.seedSecret("vault", "db-password", "s3cr3t")
      val secrets = scope.secrets(SecretStoreName("vault"))
      assertEquals(secrets.get("db-password"), "s3cr3t")

  test("secrets get throws DaprSecretsException for missing key"):
    runSafe:
      val scope = MockDaprScope()
      val secrets = scope.secrets(SecretStoreName("vault"))
      var exOpt: Exception | Null = null
      try secrets.get("nonexistent")
      catch case e: DaprSecretsException => exOpt = e
      assert(exOpt != null)

  test("secrets get throws DaprException (base type) for missing key"):
    runSafe:
      val scope = MockDaprScope()
      val secrets = scope.secrets(SecretStoreName("vault"))
      var exOpt: Exception | Null = null
      try secrets.get("nonexistent")
      catch case e: DaprException => exOpt = e
      assert(exOpt != null)

  test("secrets getBulk returns all seeded values"):
    runSafe:
      val scope = MockDaprScope()
      scope.seedSecret("vault", "a", "1")
      scope.seedSecret("vault", "b", "2")
      val secrets = scope.secrets(SecretStoreName("vault"))
      assertEquals(secrets.getBulk(), Map("a" -> "1", "b" -> "2"))

  // -------------------------------------------------------------------------
  // Configuration through mock scope
  // -------------------------------------------------------------------------

  test("config get returns seeded items"):
    runSafe:
      val scope = MockDaprScope()
      scope.seedConfig("app-config", "log-level", ConfigItem("log-level", "INFO", "1"))
      val config = scope.config(ConfigStoreName("app-config"))
      val result = config.get(Seq("log-level"))
      assertEquals(result("log-level").value, "INFO")

  test("config get returns empty map for unknown keys"):
    runSafe:
      val scope = MockDaprScope()
      val config = scope.config(ConfigStoreName("app-config"))
      assert(config.get(Seq("unknown")).isEmpty)

  // -------------------------------------------------------------------------
  // Distributed lock through mock scope
  // -------------------------------------------------------------------------

  test("lock tryLock succeeds on first attempt"):
    runSafe:
      val scope = MockDaprScope()
      val lock = scope.lock(StoreName("lock-store"))
      val acquired = lock.tryLock("resource-1", "owner-1", 30)
      assert(acquired)

  test("lock tryLock fails if already held"):
    runSafe:
      val scope = MockDaprScope()
      val lock = scope.lock(StoreName("lock-store"))
      lock.tryLock("resource-1", "owner-1", 30)
      val acquired = lock.tryLock("resource-1", "owner-2", 30)
      assert(!acquired)

  test("lock unlock releases the lock"):
    runSafe:
      val scope = MockDaprScope()
      val lock = scope.lock(StoreName("lock-store"))
      lock.tryLock("resource-1", "owner-1", 30)
      val status = lock.unlock("resource-1", "owner-1")
      assertEquals(status, UnlockStatus.Success)

  test("lock unlock on non-held resource returns LockNotFound"):
    runSafe:
      val scope = MockDaprScope()
      val lock = scope.lock(StoreName("lock-store"))
      val status = lock.unlock("no-such-resource", "owner-1")
      assertEquals(status, UnlockStatus.LockNotFound)

  test("lock unlock with wrong owner returns InternalError"):
    runSafe:
      val scope = MockDaprScope()
      val lock = scope.lock(StoreName("lock-store"))
      lock.tryLock("resource-1", "owner-1", 30)
      val status = lock.unlock("resource-1", "owner-2")
      assertEquals(status, UnlockStatus.InternalError)

  // -------------------------------------------------------------------------
  // DaprScope via withScope helper
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
    runSafe:
      val scope = MockDaprScope()
      val state = scope.state(StoreName("test-store"))
      state.save("k", "v")
      scope.close()
      var exOpt: Exception | Null = null
      try state.get[String]("k")
      catch case e: IllegalStateException => exOpt = e
      assert(exOpt != null)

  test("scope factory throws IllegalStateException after close"):
    val scope = MockDaprScope()
    scope.close()
    intercept[IllegalStateException]:
      scope.state(StoreName("test-store"))

  test("pubsub operation throws IllegalStateException after scope close"):
    runSafe:
      val scope  = MockDaprScope()
      val pubsub = scope.pubsub(PubSubName("ps"))
      scope.close()
      var exOpt: Exception | Null = null
      try pubsub.publish(Topic("t"), "msg")
      catch case e: IllegalStateException => exOpt = e
      assert(exOpt != null)

  test("secrets operation throws IllegalStateException after scope close"):
    runSafe:
      val scope   = MockDaprScope()
      scope.seedSecret("vault", "k", "v")
      val secrets = scope.secrets(SecretStoreName("vault"))
      scope.close()
      var exOpt: Exception | Null = null
      try secrets.get("k")
      catch case e: IllegalStateException => exOpt = e
      assert(exOpt != null)
