//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.derivation.*
import scala.collection.mutable

// Recording fake + derive trait for JvmCapabilityDerivationTest — the jobs slice of
// CapabilityDerivationFixtures. JVM-only: JobsCapability and Jobs.derive exist only on the JVM
// (the Dapr JS SDK has no jobs API). Unused capability methods are stubbed with `???` (never
// called by the derived facade under test).

// ---- Jobs -------------------------------------------------------------------

trait JobClient:
  def recur(data: Req, schedule: JobSchedule)(using JobsCapability, JsonCodec[Req]): Unit
  def once(data: Req, dueTime: java.time.Instant)(using JobsCapability, JsonCodec[Req]): Unit
  @name("recur") def fetch()(using JobsCapability): Option[JobDetails]
lazy val JobClient: JobClient = Jobs.derive[JobClient]

@scala.caps.assumeSafe
final class FakeJobs extends JobsCapability:
  val log: mutable.ListBuffer[String] = mutable.ListBuffer.empty
  def schedule[T: JsonCodec](
      name: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant],
      repeats: Option[Int],
      ttl: Option[java.time.Instant],
  ): Unit =
    log += s"schedule|${name.value}|${summon[JsonCodec[T]].encode(data)}"
  def scheduleOnce[T: JsonCodec](
      name: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant],
  ): Unit =
    log += s"once|${name.value}|${summon[JsonCodec[T]].encode(data)}"
  def get(name: JobName): Option[JobDetails] =
    log += s"get|${name.value}"
    None
  def delete(name: JobName): Unit = ???
