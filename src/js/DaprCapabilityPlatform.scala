//> using target.platform "scala-js"
package dapr4s

/** Scala.js half of the [[DaprCapability]] surface — deliberately empty.
  *
  * The Dapr JS SDK (`@dapr/dapr` 3.x) has no jobs or conversation API, so the `jobs` and `conversation` factory methods
  * exist only on the JVM platform trait (`src/jvm/DaprCapabilityPlatform.scala`). Using them from Scala.js code is a
  * compile-time error by design — there is no method to call, instead of a runtime `UnsupportedOperationException` (see
  * the platform-surface note on [[DaprCapability]]).
  *
  * WHY @assumeSafe: kept for symmetry with the JVM twin so [[DaprCapability]] composes the same trait shape on both
  * platforms; an empty trait asserts nothing.
  */
@scala.caps.assumeSafe
trait DaprCapabilityPlatform

/** Scala.js half of the [[DaprCapability$ DaprCapability companion]] transformer API — deliberately empty for the same
  * reason as [[DaprCapabilityPlatform]]: the Dapr JS SDK has no jobs or conversation API, so the `jobs`/`conversation`
  * transformer methods exist only on the JVM twin and using them from Scala.js code is a compile-time error by design.
  *
  * WHY @assumeSafe: symmetry with the JVM twin; an empty trait asserts nothing.
  */
@scala.caps.assumeSafe
trait DaprCapabilityCompanionPlatform
