package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import munit.FunSuite

import java.nio.charset.StandardCharsets
import scala.collection.immutable.ArraySeq

@scala.caps.assumeSafe
class CharsetsTest extends FunSuite:

  test("encodeString returns the UTF-8 bytes of the text"):
    val text = "the quick brown fox"
    assertEquals(Charsets.encodeString(text, Charsets.Utf8), ArraySeq.unsafeWrapArray(text.getBytes("UTF-8")))

  test("encodeString honours a non-UTF-8 charset"):
    val text = "café"
    assertEquals(
      Charsets.encodeString(text, Charsets.Iso8859_1),
      ArraySeq.unsafeWrapArray(text.getBytes(StandardCharsets.ISO_8859_1)),
    )

  test("encodeString of the empty string is empty"):
    assertEquals(Charsets.encodeString("", Charsets.Utf8), ArraySeq.empty[Byte])

  test("apply looks up a charset by name"):
    assertEquals(Charsets("UTF-8"), StandardCharsets.UTF_8)
    assertEquals(Charsets("ISO-8859-1"), StandardCharsets.ISO_8859_1)
