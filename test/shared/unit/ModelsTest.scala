package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import munit.FunSuite

@scala.caps.assumeSafe
class ModelsTest extends FunSuite:

  // -------------------------------------------------------------------------
  // Opaque type construction and value extraction
  // -------------------------------------------------------------------------

  test("StateStoreName round-trips through apply/value"):
    val n = StateStoreName("my-store")
    assertEquals(n.value, "my-store")

  test("PubSubName round-trips"):
    val n = PubSubName("my-pubsub")
    assertEquals(n.value, "my-pubsub")

  test("Topic round-trips"):
    val t = Topic("orders")
    assertEquals(t.value, "orders")

  test("AppId round-trips"):
    val a = AppId("order-service")
    assertEquals(a.value, "order-service")

  test("SecretStoreName round-trips"):
    val s = SecretStoreName("vault")
    assertEquals(s.value, "vault")

  test("ConfigurationStoreName round-trips"):
    val c = ConfigurationStoreName("app-config")
    assertEquals(c.value, "app-config")

  test("BindingName round-trips"):
    val b = BindingName("cron")
    assertEquals(b.value, "cron")

  test("ETag round-trips"):
    val e = ETag("abc123")
    assertEquals(e.value, "abc123")

  test("StateQuery round-trips"):
    val q = StateQuery("{\"filter\":{}}")
    assertEquals(q.value, "{\"filter\":{}}")

  test("Opaque types prevent mix-up at compile time"):
    val store: StateStoreName = StateStoreName("s")
    val pubsub: PubSubName = PubSubName("p")
    assertEquals(store.value, "s")
    assertEquals(pubsub.value, "p")

  // -------------------------------------------------------------------------
  // StateEntry
  // -------------------------------------------------------------------------

  test("StateEntry with value and etag"):
    val entry = StateEntry(Some(42), Some(ETag("v1")))
    assertEquals(entry.value, Some(42))
    assertEquals(entry.etag, Some(ETag("v1")))

  test("StateEntry absent"):
    val entry = StateEntry[String](None, None)
    assert(entry.value.isEmpty)
    assert(entry.etag.isEmpty)

  // -------------------------------------------------------------------------
  // ConfigurationItem
  // -------------------------------------------------------------------------

  test("ConfigurationItem default metadata is empty"):
    val item = ConfigurationItem(ConfigurationKey("key"), ConfigurationValue("value"), ConfigurationVersion("1"))
    assertEquals(item.metadata, Map.empty)

  test("ConfigurationItem with metadata"):
    val item =
      ConfigurationItem(
        ConfigurationKey("key"),
        ConfigurationValue("value"),
        ConfigurationVersion("2"),
        Map(MetadataKey("a") -> MetadataValue("b")),
      )
    assertEquals(item.metadata(MetadataKey("a")), MetadataValue("b"))

  // -------------------------------------------------------------------------
  // StateOp
  // -------------------------------------------------------------------------

  test("UpsertOp stores key and pre-encoded value"):
    val op = StateOp.UpsertOp[String](StateStoreKey("k"), "v")
    assertEquals(op.key, StateStoreKey("k"))
    assert(op.encodedValue.value.nonEmpty)
    assertEquals(op.etag, None)

  test("UpsertOp with etag"):
    val op = StateOp.UpsertOp[String](StateStoreKey("k"), "v", Some(ETag("e1")))
    assertEquals(op.etag, Some(ETag("e1")))

  test("DeleteOp stores key"):
    val op = StateOp.DeleteOp(StateStoreKey("k"))
    assertEquals(op.key, StateStoreKey("k"))
    assertEquals(op.etag, None)

  // -------------------------------------------------------------------------
  // New model types
  // -------------------------------------------------------------------------

  test("UnlockStatus values are distinct"):
    assert(UnlockStatus.Success != UnlockStatus.LockNotFound)
    assert(UnlockStatus.LockNotFound != UnlockStatus.InternalError)
    assert(UnlockStatus.Success != UnlockStatus.InternalError)

  test("BulkPublishEntry holds entryId and event"):
    val entry = BulkPublishEntry(BulkEntryId("id-1"), "event-data")
    assertEquals(entry.entryId, BulkEntryId("id-1"))
    assertEquals(entry.event, "event-data")

  test("BulkPublishResult with no failures"):
    val result = BulkPublishResult(List.empty)
    assert(result.failedEntries.isEmpty)

  test("BulkPublishResult with failed entries"):
    val result = BulkPublishResult(List(BulkEntryId("id-2"), BulkEntryId("id-3")))
    assertEquals(result.failedEntries, List(BulkEntryId("id-2"), BulkEntryId("id-3")))

  // -------------------------------------------------------------------------
  // Exceptions
  // -------------------------------------------------------------------------

  test("ETagMismatchException message contains key and etag"):
    val ex = ETagMismatchException(StateStoreKey("my-key"), ETag("abc"))
    assert(ex.getMessage.contains("my-key"))
    assert(ex.getMessage.contains("abc"))

  test("JsonDecodeException message"):
    val ex = JsonDecodeException("bad json")
    assertEquals(ex.getMessage, "bad json")

  // -------------------------------------------------------------------------
  // Non-empty validation for opaque types
  // -------------------------------------------------------------------------

  test("StateStoreName rejects empty string"):
    intercept[IllegalArgumentException] { StateStoreName("") }

  test("PubSubName rejects empty string"):
    intercept[IllegalArgumentException] { PubSubName("") }

  test("Topic rejects empty string"):
    intercept[IllegalArgumentException] { Topic("") }

  test("AppId rejects empty string"):
    intercept[IllegalArgumentException] { AppId("") }

  test("SecretStoreName rejects empty string"):
    intercept[IllegalArgumentException] { SecretStoreName("") }

  test("ConfigurationStoreName rejects empty string"):
    intercept[IllegalArgumentException] { ConfigurationStoreName("") }

  test("BindingName rejects empty string"):
    intercept[IllegalArgumentException] { BindingName("") }

  test("ETag accepts empty string (valid for operations that ignore ETag)"):
    val e = ETag("")
    assertEquals(e.value, "")

  // -------------------------------------------------------------------------
  // Actor and Workflow opaque types
  // -------------------------------------------------------------------------

  test("ActorType round-trips"):
    val t = ActorType("OrderActor")
    assertEquals(t.value, "OrderActor")

  test("ActorType rejects empty string"):
    intercept[IllegalArgumentException] { ActorType("") }

  test("ActorId round-trips (can be empty)"):
    val id = ActorId("actor-123")
    assertEquals(id.value, "actor-123")

  test("ActorId accepts empty string"):
    val id = ActorId("")
    assertEquals(id.value, "")

  test("WorkflowName round-trips"):
    val n = WorkflowName("OrderWorkflow")
    assertEquals(n.value, "OrderWorkflow")

  test("WorkflowName rejects empty string"):
    intercept[IllegalArgumentException] { WorkflowName("") }

  test("WorkflowInstanceId round-trips"):
    val id = WorkflowInstanceId("abc-123")
    assertEquals(id.value, "abc-123")

  // -------------------------------------------------------------------------
  // WorkflowStatus and WorkflowSnapshot
  // -------------------------------------------------------------------------

  test("WorkflowStatus enum values are distinct"):
    val all = List(
      WorkflowStatus.Running,
      WorkflowStatus.Completed,
      WorkflowStatus.ContinuedAsNew,
      WorkflowStatus.Failed,
      WorkflowStatus.Canceled,
      WorkflowStatus.Terminated,
      WorkflowStatus.Pending,
      WorkflowStatus.Suspended,
    )
    assertEquals(all.distinct.size, 8)

  test("WorkflowSnapshot holds all fields"):
    val now = java.time.Instant.now()
    val snap = WorkflowSnapshot(
      name = WorkflowName("MyWorkflow"),
      instanceId = WorkflowInstanceId("inst-1"),
      status = WorkflowStatus.Running,
      createdAt = now,
      lastUpdatedAt = now,
      serializedInput = Some(SerializedJson("{\"x\":1}")),
      serializedOutput = None,
    )
    assertEquals(snap.name.value, "MyWorkflow")
    assertEquals(snap.instanceId.value, "inst-1")
    assertEquals(snap.status, WorkflowStatus.Running)
    assertEquals(snap.serializedInput, Some(SerializedJson("{\"x\":1}")))
    assertEquals(snap.serializedOutput, None)

  // -------------------------------------------------------------------------
  // Crypto opaque types
  // -------------------------------------------------------------------------

  test("CryptoComponentName round-trips"):
    assertEquals(CryptoComponentName("localstorage").value, "localstorage")

  test("CryptoComponentName rejects empty string"):
    intercept[IllegalArgumentException] { CryptoComponentName("") }

  test("CryptoKeyName round-trips"):
    assertEquals(CryptoKeyName("rsa-key").value, "rsa-key")

  test("CryptoKeyName rejects empty string"):
    intercept[IllegalArgumentException] { CryptoKeyName("") }

  test("KeyWrapAlgorithm constants and custom values"):
    assertEquals(KeyWrapAlgorithm.Rsa.value, "RSA")
    assertEquals(KeyWrapAlgorithm.Aes.value, "AES")
    assertEquals(KeyWrapAlgorithm("A256KW").value, "A256KW")

  test("KeyWrapAlgorithm rejects empty string"):
    intercept[IllegalArgumentException] { KeyWrapAlgorithm("") }

  // -------------------------------------------------------------------------
  // Jobs
  // -------------------------------------------------------------------------

  test("JobName round-trips"):
    assertEquals(JobName("nightly-report").value, "nightly-report")

  test("JobName rejects empty string"):
    intercept[IllegalArgumentException] { JobName("") }

  // JobSchedule/JobDetails and the Conversation* models are JVM-only (the Dapr JS SDK has no
  // jobs or conversation API) — their cases live in test/jvm/unit/JvmModelsTest.scala.

  // -------------------------------------------------------------------------
  // Conversation
  // -------------------------------------------------------------------------

  test("ConversationComponentName round-trips"):
    assertEquals(ConversationComponentName("echo").value, "echo")

  test("ConversationComponentName rejects empty string"):
    intercept[IllegalArgumentException] { ConversationComponentName("") }
