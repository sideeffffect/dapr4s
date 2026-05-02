package dapr.safe.test.unit

import dapr.safe.*
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
    val store: StoreName    = StoreName("s")
    val pubsub: PubSubName  = PubSubName("p")
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
    val item = ConfigItem("key", "value", "1")
    assertEquals(item.metadata, Map.empty)

  test("ConfigItem with metadata"):
    val item = ConfigItem("key", "value", "2", Map("a" -> "b"))
    assertEquals(item.metadata("a"), "b")

  // -------------------------------------------------------------------------
  // StateOp
  // -------------------------------------------------------------------------

  test("UpsertOp stores key and pre-encoded value"):
    val op = StateOp.UpsertOp[String]("k", "v")
    assertEquals(op.key, "k")
    assert(op.encodedValue.nonEmpty)
    assertEquals(op.etag, None)

  test("UpsertOp with etag"):
    val op = StateOp.UpsertOp[String]("k", "v", Some(ETag("e1")))
    assertEquals(op.etag, Some(ETag("e1")))

  test("DeleteOp stores key"):
    val op = StateOp.DeleteOp("k")
    assertEquals(op.key, "k")
    assertEquals(op.etag, None)

  // -------------------------------------------------------------------------
  // New model types
  // -------------------------------------------------------------------------

  test("UnlockStatus values are distinct"):
    assert(UnlockStatus.Success.code == 0)
    assert(UnlockStatus.LockNotFound.code == 1)
    assert(UnlockStatus.InternalError.code == 2)

  test("BulkPublishEntry holds entryId and event"):
    val entry = BulkPublishEntry("id-1", "event-data")
    assertEquals(entry.entryId, "id-1")
    assertEquals(entry.event, "event-data")

  test("BulkPublishResult with no failures"):
    val result = BulkPublishResult(List.empty)
    assert(result.failedEntries.isEmpty)

  test("BulkPublishResult with failed entries"):
    val result = BulkPublishResult(List("id-2", "id-3"))
    assertEquals(result.failedEntries, List("id-2", "id-3"))

  // -------------------------------------------------------------------------
  // Exception hierarchy
  // -------------------------------------------------------------------------

  test("DaprException message"):
    val ex = DaprException("something went wrong")
    assertEquals(ex.getMessage, "something went wrong")

  test("DaprException with cause"):
    val cause = RuntimeException("root cause")
    val ex    = DaprException("wrapper", cause)
    assertEquals(ex.getCause, cause)

  test("DaprException with null cause has null getCause"):
    val ex = DaprException("test", null)
    assert(ex.getCause == null)

  test("ETagMismatchException message contains key and etag"):
    val ex = ETagMismatchException("my-key", ETag("abc"))
    assert(ex.getMessage.contains("my-key"))
    assert(ex.getMessage.contains("abc"))
    assert(ex.isInstanceOf[DaprException])
    assert(ex.isInstanceOf[DaprStateException])

  test("JsonDecodeException is a DaprException"):
    val ex = JsonDecodeException("bad json")
    assert(ex.isInstanceOf[DaprException])
    assertEquals(ex.getMessage, "bad json")

  test("DaprStateException is a DaprException"):
    val ex = DaprStateException("state error")
    assert(ex.isInstanceOf[DaprException])

  test("DaprPubSubException is a DaprException"):
    val ex = DaprPubSubException("pubsub error")
    assert(ex.isInstanceOf[DaprException])

  test("DaprSecretsException is a DaprException"):
    val ex = DaprSecretsException("secrets error")
    assert(ex.isInstanceOf[DaprException])

  test("DaprLockException is a DaprException"):
    val ex = DaprLockException("lock error")
    assert(ex.isInstanceOf[DaprException])

  test("StateTransactionException is a DaprStateException"):
    val ex = StateTransactionException("tx error")
    assert(ex.isInstanceOf[DaprStateException])
    assert(ex.isInstanceOf[DaprException])

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
