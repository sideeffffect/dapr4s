//> using target.platform "jvm"
package dapr4s

import language.experimental.safe

/** JVM-only [[PemPath]] interop: construct a [[PemPath]] directly from a `java.nio.file.Path` — `PemPath(path)` — so
  * JVM callers are not forced through `PemPath(path.toString)` by hand. Lives in a jvm-tagged file because the
  * cross-platform core must stay free of `java.nio.file` (absent on Scala.js).
  *
  * Declared as an extension method on the companion object: when no `PemPath.apply` overload matches the argument type,
  * Scala 3 falls back to extension-method resolution on the receiver, so `PemPath(path)` call sites resolve here while
  * the shared `PemPath(string)` overload keeps working on both platforms unchanged.
  */
extension (companion: PemPath.type) def apply(path: java.nio.file.Path): PemPath = PemPath(path.toString)
