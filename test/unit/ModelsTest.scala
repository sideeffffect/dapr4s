package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import munit.FunSuite

@scala.caps.assumeSafe
class ModelsTest extends FunSuite:

  // -------------------------------------------------------------------------
  // Opaque type construction and value extraction
  // -------------------------------------------------------------------------

  test("StoreName round-trips through apply/value"):
    val n = StoreName("my-store")
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

  test("ConfigStoreName round-trips"):
    val c = ConfigStoreName("app-config")
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
    val store: StoreName = StoreName("s")
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
  // ConfigItem
  // -------------------------------------------------------------------------

  test("ConfigItem default metadata is empty"):
    val item = ConfigItem(ConfigKey("key"), "value", ConfigVersion("1"))
    assertEquals(item.metadata, Map.empty)

  test("ConfigItem with metadata"):
    val item = ConfigItem(ConfigKey("key"), "value", ConfigVersion("2"), Map(MetadataKey("a") -> MetadataValue("b")))
    assertEquals(item.metadata(MetadataKey("a")), MetadataValue("b"))

  // -------------------------------------------------------------------------
  // StateOp
  // -------------------------------------------------------------------------

  test("UpsertOp stores key and pre-encoded value"):
    val op = StateOp.UpsertOp[String](StateKey("k"), "v")
    assertEquals(op.key, StateKey("k"))
    assert(op.encodedValue.value.nonEmpty)
    assertEquals(op.etag, None)

  test("UpsertOp with etag"):
    val op = StateOp.UpsertOp[String](StateKey("k"), "v", Some(ETag("e1")))
    assertEquals(op.etag, Some(ETag("e1")))

  test("DeleteOp stores key"):
    val op = StateOp.DeleteOp(StateKey("k"))
    assertEquals(op.key, StateKey("k"))
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
    val ex = ETagMismatchException(StateKey("my-key"), ETag("abc"))
    assert(ex.getMessage.contains("my-key"))
    assert(ex.getMessage.contains("abc"))

  test("JsonDecodeException message"):
    val ex = JsonDecodeException("bad json")
    assertEquals(ex.getMessage, "bad json")

  // -------------------------------------------------------------------------
  // Non-empty validation for opaque types
  // -------------------------------------------------------------------------

  test("StoreName rejects empty string"):
    intercept[IllegalArgumentException] { StoreName("") }

  test("PubSubName rejects empty string"):
    intercept[IllegalArgumentException] { PubSubName("") }

  test("Topic rejects empty string"):
    intercept[IllegalArgumentException] { Topic("") }

  test("AppId rejects empty string"):
    intercept[IllegalArgumentException] { AppId("") }

  test("SecretStoreName rejects empty string"):
    intercept[IllegalArgumentException] { SecretStoreName("") }

  test("ConfigStoreName rejects empty string"):
    intercept[IllegalArgumentException] { ConfigStoreName("") }

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

  test("JobSchedule cases hold their data"):
    import scala.concurrent.duration.DurationInt
    assertEquals(JobSchedule.Cron("0 30 * * * *").asInstanceOf[JobSchedule.Cron].expression, "0 30 * * * *")
    assertEquals(JobSchedule.Every(5.seconds).asInstanceOf[JobSchedule.Every].period, 5.seconds)

  test("JobDetails holds all fields"):
    val now = java.time.Instant.now()
    val d = JobDetails(
      name = JobName("j"),
      data = Some(SerializedJson("\"x\"")),
      scheduleExpression = Some("@every 5s"),
      dueTime = Some(now),
      repeats = Some(3),
      ttl = None,
    )
    assertEquals(d.name, JobName("j"))
    assertEquals(d.repeats, Some(3))
    assertEquals(d.ttl, None)

  // -------------------------------------------------------------------------
  // Conversation
  // -------------------------------------------------------------------------

  test("ConversationComponentName round-trips"):
    assertEquals(ConversationComponentName("echo").value, "echo")

  test("ConversationComponentName rejects empty string"):
    intercept[IllegalArgumentException] { ConversationComponentName("") }

  test("ChatMessage smart constructors set the role"):
    assertEquals(ChatMessage.system("s").role, ChatRole.System)
    assertEquals(ChatMessage.user("u").role, ChatRole.User)
    assertEquals(ChatMessage.assistant("a").role, ChatRole.Assistant)
    assertEquals(ChatMessage.developer("d").role, ChatRole.Developer)
    assertEquals(ChatMessage.tool("t", Some("fn")).name, Some("fn"))

  test("ChatRole enum values are distinct"):
    assertEquals(
      List(ChatRole.System, ChatRole.User, ChatRole.Assistant, ChatRole.Tool, ChatRole.Developer).distinct.size,
      5,
    )
