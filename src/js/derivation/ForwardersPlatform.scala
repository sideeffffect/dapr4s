//> using target.platform "scala-js"
package dapr4s.derivation

/** Scala.js half of [[Forwarders]] — deliberately empty.
  *
  * The jobs forwarders (`jobSchedule`/`jobScheduleOnce`/`jobGet`) exist only on the JVM twin: they forward to the
  * JVM-only `dapr4s.JobsCapability` (the Dapr JS SDK has no jobs API), and the `Jobs.derive` macro that generates calls
  * to them is itself JVM-only. On Scala.js neither the capability nor the macro exists, so this trait has nothing to
  * contribute — referencing a jobs forwarder from JS code is a compile-time error by design.
  *
  * WHY @assumeSafe: symmetry with the JVM twin; an empty trait asserts nothing.
  */
@scala.caps.assumeSafe
trait ForwardersPlatform
