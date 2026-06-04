package dapr4s

import java.nio.charset.{Charset, StandardCharsets}
import scala.collection.immutable.ArraySeq

/** Charset helpers, exposed so capture-checked ("safe mode") code can name a charset and turn text into bytes.
  *
  * Safe code may reference neither the `java.nio.charset.StandardCharsets` fields nor `Charset.forName`, so these
  * tagged members are the only way to obtain a `Charset` and encode a `String` from a safe module.
  */
@scala.caps.assumeSafe
object Charsets:
  val Utf8: Charset = StandardCharsets.UTF_8
  val Utf16: Charset = StandardCharsets.UTF_16
  val UsAscii: Charset = StandardCharsets.US_ASCII
  val Iso8859_1: Charset = StandardCharsets.ISO_8859_1

  /** Look up a `Charset` by name, e.g. `Charsets("UTF-8")`. Throws if the name is unknown or illegal. */
  def apply(name: String): Charset = Charset.forName(name).nn

  /** Encode `text` into an immutable byte sequence using `charset`.
    *
    * A pure replacement for the `ArraySeq.unsafeWrapArray(text.getBytes(charset))` idiom, so callers never handle the
    * mutable `Array[Byte]` the JDK returns.
    */
  def encodeString(text: String, charset: Charset): ArraySeq[Byte] =
    ArraySeq.unsafeWrapArray(text.getBytes(charset).nn)
