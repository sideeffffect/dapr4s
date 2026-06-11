package dapr4s

import language.experimental.safe

/** Filesystem path to a PEM file (TLS certificate, private key, or CA bundle).
  *
  * Used by [[SidecarConfig]] for the gRPC TLS material. Modelled as a string rather than `java.nio.file.Path` so the
  * configuration cross-compiles to Scala.js, where `java.nio.file` does not exist. On the JVM,
  * `PemPath(path: java.nio.file.Path)` also works directly (a jvm-only companion extension — see
  * `src/jvm/PemPathJvm.scala`); the reverse direction is `java.nio.file.Path.of(pemPath.value)`.
  *
  * Must not be empty.
  */
opaque type PemPath = String
object PemPath:
  def apply(s: String): PemPath =
    require(s.nonEmpty, "PemPath must not be empty")
    s
  extension (p: PemPath) def value: String = p
