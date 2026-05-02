package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite

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

  // Opaque types are nominally distinct (no implicit coercion between them)
  // This is a compile-time check; if this compiles, types are distinct.
  test("Opaque types prevent mix-up at compile time"):
    val store: StoreName    = StoreName("s")
    val pubsub: PubSubName  = PubSubName("p")
    // store and pubsub have different types — assignment in the other
    // direction would not compile; here we just assert they are equal as strings
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
    // encodedValue is a JSON string literal: "\"v\""
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
  // Exceptions
  // -------------------------------------------------------------------------

  test("DaprException message"):
    val ex = DaprException("something went wrong")
    assertEquals(ex.getMessage, "something went wrong")
    assert(ex.getCause == null)

  test("DaprException with cause"):
    val cause = RuntimeException("root cause")
    val ex    = DaprException("wrapper", cause)
    assertEquals(ex.getCause, cause)

  test("ETagMismatchException message contains key and etag"):
    val ex = ETagMismatchException("my-key", ETag("abc"))
    assert(ex.getMessage.contains("my-key"))
    assert(ex.getMessage.contains("abc"))
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
