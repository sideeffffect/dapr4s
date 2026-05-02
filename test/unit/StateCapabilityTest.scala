package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite
import language.experimental.saferExceptions

// NOTE: Despite its name, StateCapabilityTest covers the full mock-based test suite
// including State, PubSub, Secrets, Configuration, Lock, and closed-scope invariants.
@scala.caps.assumeSafe
class StateCapabilityTest extends FunSuite:

  /** Provide CanThrow[Exception] via a method body so no lambda ever captures canThrowAny. Multiple calls from
    * different lambdas are safe — each creates a fresh method stack frame.
    */
  def runSafe[T](body: CanThrow[Exception] ?=> T): T =
    given CanThrow[Exception] = unsafeExceptions.canThrowAny
    body

  /** Run a block against a fresh [[MockDaprCapability]]. Uses runSafe internally so canThrowAny is never captured in
    * any lambda.
    */
  def withScope[T](body: (DaprCapability, CanThrow[Exception]) ?=> T): T =
    given scope: DaprCapability = MockDaprCapability()
    runSafe:
      body

  // -------------------------------------------------------------------------
  // get / save
  // -------------------------------------------------------------------------

  test("save then get returns the saved value"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("key1"), "hello")
      assertEquals(state.get[String](StateKey("key1")), Some("hello"))

  test("get on missing key returns None"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      assertEquals(state.get[String](StateKey("missing")), None)

  test("save overwrites previous value"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "first")
      state.save(StateKey("k"), "second")
      assertEquals(state.get[String](StateKey("k")), Some("second"))

  test("save and get Int"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("n"), 42)
      assertEquals(state.get[Int](StateKey("n")), Some(42))

  // -------------------------------------------------------------------------
  // getWithETag
  // -------------------------------------------------------------------------

  test("getWithETag returns value and etag after save"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v")
      val entry = state.getWithETag[String](StateKey("k"))
      assertEquals(entry.value, Some("v"))
      assert(entry.etag.isDefined)

  test("getWithETag on missing key returns empty entry"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      val entry = state.getWithETag[String](StateKey("absent"))
      assertEquals(entry.value, None)
      assertEquals(entry.etag, None)

  // -------------------------------------------------------------------------
  // getBulk / saveBulk
  // -------------------------------------------------------------------------

  test("saveBulk then getBulk returns all values"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.saveBulk[Int](Seq(StateKey("a") -> 1, StateKey("b") -> 2, StateKey("c") -> 3))
      val results = state.getBulk[Int](Seq(StateKey("a"), StateKey("b"), StateKey("c")))
      assertEquals(results(StateKey("a")).value, Some(1))
      assertEquals(results(StateKey("b")).value, Some(2))
      assertEquals(results(StateKey("c")).value, Some(3))

  test("getBulk returns missing key as None value"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("exists"), "v")
      val results = state.getBulk[String](Seq(StateKey("exists"), StateKey("missing")))
      assertEquals(results(StateKey("exists")).value, Some("v"))
      assertEquals(results(StateKey("missing")).value, None)

  // -------------------------------------------------------------------------
  // saveWithETag
  // -------------------------------------------------------------------------

  test("saveWithETag succeeds with correct etag"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v1")
      val entry = state.getWithETag[String](StateKey("k"))
      val etag = entry.etag.getOrElse(fail("expected etag after save"))
      state.saveWithETag(StateKey("k"), "v2", etag)
      assertEquals(state.get[String](StateKey("k")), Some("v2"))

  test("saveWithETag throws ETagMismatchException on wrong etag"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v1")
      var exOpt: Exception | Null = null
      try state.saveWithETag(StateKey("k"), "v2", ETag("wrong-etag"))
      catch case e: ETagMismatchException => exOpt = e
      assert(exOpt != null)

  test("saveWithETag throws ETagMismatchException when key does not exist"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      var exOpt: Exception | Null = null
      try state.saveWithETag(StateKey("nonexistent"), "v", ETag("any-etag"))
      catch case e: ETagMismatchException => exOpt = e
      assert(exOpt != null)

  test("ETagMismatchException is a DaprStateException"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v1")
      var exOpt: Exception | Null = null
      try state.saveWithETag(StateKey("k"), "v2", ETag("wrong-etag"))
      catch case e: DaprStateException => exOpt = e
      assert(exOpt != null && exOpt.isInstanceOf[ETagMismatchException])

  // -------------------------------------------------------------------------
  // delete
  // -------------------------------------------------------------------------

  test("delete removes a key"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v")
      state.delete(StateKey("k"))
      assertEquals(state.get[String](StateKey("k")), None)

  test("delete on absent key is a no-op"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.delete(StateKey("nonexistent")) // must not throw

  // -------------------------------------------------------------------------
  // deleteWithETag
  // -------------------------------------------------------------------------

  test("deleteWithETag succeeds with correct etag"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v")
      val etag = state.getWithETag[String](StateKey("k")).etag.getOrElse(fail("expected etag after save"))
      state.deleteWithETag(StateKey("k"), etag)
      assertEquals(state.get[String](StateKey("k")), None)

  test("deleteWithETag throws ETagMismatchException on wrong etag"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v")
      var exOpt: Exception | Null = null
      try state.deleteWithETag(StateKey("k"), ETag("wrong"))
      catch case e: ETagMismatchException => exOpt = e
      assert(exOpt != null)

  // -------------------------------------------------------------------------
  // transaction
  // -------------------------------------------------------------------------

  test("transaction upsert inserts new key"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.transaction(Seq(StateOp.UpsertOp[String](StateKey("newKey"), "newVal")))
      assert(state.get[String](StateKey("newKey")).isDefined)

  test("transaction delete removes existing key"):
    withScope:
      val state = summon[DaprCapability].state(StoreName("test-store"))
      state.save(StateKey("k"), "v")
      state.transaction(Seq(StateOp.DeleteOp(StateKey("k"))))
      assertEquals(state.get[String](StateKey("k")), None)

  // -------------------------------------------------------------------------
  // PubSub through mock scope
  // -------------------------------------------------------------------------

  test("publish records event in mock scope"):
    runSafe:
      val scope = MockDaprCapability()
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
      val scope = MockDaprCapability()
      val pubsub = scope.pubsub(PubSubName("my-pubsub"))
      pubsub.publishWithMetadata(Topic("orders"), "payload", Map("k" -> "v"))
      val events = scope.publishedEvents
      assertEquals(events.length, 1)
      val (_, _, _, meta) = events(0)
      assertEquals(meta, Map("k" -> "v"))

  test("bulkPublish records all entries in mock scope"):
    runSafe:
      val scope = MockDaprCapability()
      val pubsub = scope.pubsub(PubSubName("my-pubsub"))
      val entries = Seq(
        BulkPublishEntry(BulkEntryId("1"), "event-a"),
        BulkPublishEntry(BulkEntryId("2"), "event-b"),
      )
      val result = pubsub.bulkPublish(Topic("orders"), entries)
      assertEquals(scope.publishedEvents.length, 2)
      assertEquals(result.failedEntries, List.empty)

  // -------------------------------------------------------------------------
  // Secrets through mock scope
  // -------------------------------------------------------------------------

  test("secrets get returns seeded value"):
    runSafe:
      val scope = MockDaprCapability()
      scope.seedSecret("vault", "db-password", "s3cr3t")
      val secrets = scope.secrets(SecretStoreName("vault"))
      assertEquals(secrets.get(SecretKey("db-password")), "s3cr3t")

  test("secrets get throws DaprSecretsException for missing key"):
    runSafe:
      val scope = MockDaprCapability()
      val secrets = scope.secrets(SecretStoreName("vault"))
      var exOpt: Exception | Null = null
      try secrets.get(SecretKey("nonexistent"))
      catch case e: DaprSecretsException => exOpt = e
      assert(exOpt != null)

  test("secrets get throws DaprException (base type) for missing key"):
    runSafe:
      val scope = MockDaprCapability()
      val secrets = scope.secrets(SecretStoreName("vault"))
      var exOpt: Exception | Null = null
      try secrets.get(SecretKey("nonexistent"))
      catch case e: DaprException => exOpt = e
      assert(exOpt != null)

  test("secrets getBulk returns all seeded values"):
    runSafe:
      val scope = MockDaprCapability()
      scope.seedSecret("vault", "a", "1")
      scope.seedSecret("vault", "b", "2")
      val secrets = scope.secrets(SecretStoreName("vault"))
      assertEquals(secrets.getBulk(), Map(SecretKey("a") -> "1", SecretKey("b") -> "2"))

  // -------------------------------------------------------------------------
  // Configuration through mock scope
  // -------------------------------------------------------------------------

  test("config get returns seeded items"):
    runSafe:
      val scope = MockDaprCapability()
      scope.seedConfig("app-config", "log-level", ConfigItem(ConfigKey("log-level"), "INFO", "1"))
      val config = scope.config(ConfigStoreName("app-config"))
      val result = config.get(Seq(ConfigKey("log-level")))
      assertEquals(result(ConfigKey("log-level")).value, "INFO")

  test("config get returns empty map for unknown keys"):
    runSafe:
      val scope = MockDaprCapability()
      val config = scope.config(ConfigStoreName("app-config"))
      assert(config.get(Seq(ConfigKey("unknown"))).isEmpty)

  // -------------------------------------------------------------------------
  // Distributed lock through mock scope
  // -------------------------------------------------------------------------

  test("lock tryLock succeeds on first attempt"):
    runSafe:
      val scope = MockDaprCapability()
      val lock = scope.lock(StoreName("lock-store"))
      val acquired = lock.tryLock(LockResourceId("resource-1"), LockOwner("owner-1"), 30)
      assert(acquired)

  test("lock tryLock fails if already held"):
    runSafe:
      val scope = MockDaprCapability()
      val lock = scope.lock(StoreName("lock-store"))
      lock.tryLock(LockResourceId("resource-1"), LockOwner("owner-1"), 30)
      val acquired = lock.tryLock(LockResourceId("resource-1"), LockOwner("owner-2"), 30)
      assert(!acquired)

  test("lock unlock releases the lock"):
    runSafe:
      val scope = MockDaprCapability()
      val lock = scope.lock(StoreName("lock-store"))
      lock.tryLock(LockResourceId("resource-1"), LockOwner("owner-1"), 30)
      val status = lock.unlock(LockResourceId("resource-1"), LockOwner("owner-1"))
      assertEquals(status, UnlockStatus.Success)

  test("lock unlock on non-held resource returns LockNotFound"):
    runSafe:
      val scope = MockDaprCapability()
      val lock = scope.lock(StoreName("lock-store"))
      val status = lock.unlock(LockResourceId("no-such-resource"), LockOwner("owner-1"))
      assertEquals(status, UnlockStatus.LockNotFound)

  test("lock unlock with wrong owner returns InternalError"):
    runSafe:
      val scope = MockDaprCapability()
      val lock = scope.lock(StoreName("lock-store"))
      lock.tryLock(LockResourceId("resource-1"), LockOwner("owner-1"), 30)
      val status = lock.unlock(LockResourceId("resource-1"), LockOwner("owner-2"))
      assertEquals(status, UnlockStatus.InternalError)

  // -------------------------------------------------------------------------
  // DaprCapability via withScope helper
  // -------------------------------------------------------------------------

  test("DaprCapability factory is available as context parameter"):
    withScope:
      val scope = summon[DaprCapability]
      val state = scope.state(StoreName("s"))
      state.save(StateKey("x"), 1)
      assertEquals(state.get[Int](StateKey("x")), Some(1))

  // -------------------------------------------------------------------------
  // Closed capability rejection (ClosedCapabilityRejection invariant)
  // -------------------------------------------------------------------------

  test("state operation throws IllegalStateException after scope close"):
    runSafe:
      val scope = MockDaprCapability()
      val state = scope.state(StoreName("test-store"))
      state.save(StateKey("k"), "v")
      scope.close()
      var exOpt: Exception | Null = null
      try state.get[String](StateKey("k"))
      catch case e: IllegalStateException => exOpt = e
      assert(exOpt != null)

  test("scope factory throws IllegalStateException after close"):
    val scope = MockDaprCapability()
    scope.close()
    intercept[IllegalStateException]:
      scope.state(StoreName("test-store"))

  test("pubsub operation throws IllegalStateException after scope close"):
    runSafe:
      val scope = MockDaprCapability()
      val pubsub = scope.pubsub(PubSubName("ps"))
      scope.close()
      var exOpt: Exception | Null = null
      try pubsub.publish(Topic("t"), "msg")
      catch case e: IllegalStateException => exOpt = e
      assert(exOpt != null)

  test("secrets operation throws IllegalStateException after scope close"):
    runSafe:
      val scope = MockDaprCapability()
      scope.seedSecret("vault", "k", "v")
      val secrets = scope.secrets(SecretStoreName("vault"))
      scope.close()
      var exOpt: Exception | Null = null
      try secrets.get(SecretKey("k"))
      catch case e: IllegalStateException => exOpt = e
      assert(exOpt != null)

  // -------------------------------------------------------------------------
  // Actor capability (mock)
  // -------------------------------------------------------------------------

  test("actor capability can be created from scope"):
    withScope:
      val scope = summon[DaprCapability]
      val actor = scope.actor(ActorType("OrderActor"), ActorId("order-1"))
      assertEquals(actor.actorType.value, "OrderActor")
      assertEquals(actor.actorId.value, "order-1")

  test("mock actor invoke throws UnsupportedOperationException"):
    withScope:
      val scope = summon[DaprCapability]
      val actor = scope.actor(ActorType("TestActor"), ActorId("id-1"))
      intercept[UnsupportedOperationException]:
        actor.invokeVoid(MethodName("doSomething"))

  test("actor factory throws IllegalStateException after scope close"):
    val scope = MockDaprCapability()
    scope.close()
    intercept[IllegalStateException]:
      scope.actor(ActorType("A"), ActorId("1"))

  // -------------------------------------------------------------------------
  // Workflow capability (mock)
  // -------------------------------------------------------------------------

  test("workflow.start returns a non-empty instance ID"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val id = wf.start(WorkflowName("MyWorkflow"))
      assert(id.value.nonEmpty)

  test("workflow.startWithId uses the provided ID"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val expected = WorkflowInstanceId("my-fixed-id")
      val id = wf.startWithId(WorkflowName("MyWorkflow"), expected)
      assertEquals(id, expected)

  test("workflow.getStatus returns Running for newly started instance"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val id = wf.start(WorkflowName("MyWorkflow"))
      val snap = wf.getStatus(id)
      assert(snap.isDefined)
      assertEquals(snap.get.status, WorkflowStatus.Running)

  test("workflow.getStatus returns None for unknown instance"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val snap = wf.getStatus(WorkflowInstanceId("no-such-instance"))
      assert(snap.isEmpty)

  test("workflow.suspend transitions instance to Suspended"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val id = wf.start(WorkflowName("MyWorkflow"))
      wf.suspend(id)
      assertEquals(wf.getStatus(id).get.status, WorkflowStatus.Suspended)

  test("workflow.resume transitions instance to Running"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val id = wf.start(WorkflowName("MyWorkflow"))
      wf.suspend(id)
      wf.resume(id)
      assertEquals(wf.getStatus(id).get.status, WorkflowStatus.Running)

  test("workflow.terminate transitions instance to Terminated"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val id = wf.start(WorkflowName("MyWorkflow"))
      wf.terminate(id)
      assertEquals(wf.getStatus(id).get.status, WorkflowStatus.Terminated)

  test("workflow.purge removes instance and returns true"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val id = wf.start(WorkflowName("MyWorkflow"))
      val purged = wf.purge(id)
      assert(purged)
      assert(wf.getStatus(id).isEmpty)

  test("workflow.purge returns false for unknown instance"):
    withScope:
      val scope = summon[DaprCapability]
      val wf = scope.workflow
      val purged = wf.purge(WorkflowInstanceId("ghost-id"))
      assert(!purged)

  test("workflow factory throws IllegalStateException after scope close"):
    val scope = MockDaprCapability()
    scope.close()
    intercept[IllegalStateException]:
      scope.workflow
