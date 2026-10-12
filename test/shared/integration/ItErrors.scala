package dapr4s.test.integration

import dapr4s.*
import munit.Assertions.*

/** Assertions for the typed sidecar errors (`dapr4s.DaprException`), shared by the cross-platform suites: the same
  * sidecar answer must surface as the same exception type and fields on the JVM and on Scala.js.
  */
object ItErrors:

  /** `body` must throw [[ComponentNotFoundException]] for exactly `kind`/`name`. */
  def assertComponentNotFound(kind: DaprComponentKind, name: String)(body: => Any)(using munit.Location): Unit =
    val ex = intercept[ComponentNotFoundException](body)
    assertEquals(ex.componentKind, kind)
    assertEquals(ex.name, name)
