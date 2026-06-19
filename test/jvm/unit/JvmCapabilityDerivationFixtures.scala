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
  def recur(data: Req, schedule: JobSchedule)(using AccessJobsCapability, JsonCodec[Req]): Unit
  def once(data: Req, dueTime: java.time.Instant)(using AccessJobsCapability, JsonCodec[Req]): Unit
  @name("recur") def fetch()(using AccessJobsCapability): Option[JobDetails]
lazy val JobClient: JobClient = Jobs.derive[JobClient]

@scala.caps.assumeSafe
final class FakeJobs extends AccessJobsCapability:
  val log: mutable.ListBuffer[String]              = mutable.ListBuffer.empty
  def apply(name: JobName): JobCapability^{this} =
    new FakeJob(name, log).asInstanceOf[JobCapability]

@scala.caps.assumeSafe
final class FakeJob(name: JobName, log: mutable.ListBuffer[String]) extends JobCapability:
  val jobName: JobName = name
  def schedule[T: JsonCodec](
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant],
      repeats: Option[Int],
      ttl: Option[java.time.Instant],
  ): Unit =
    log += s"schedule|${name.value}|${summon[JsonCodec[T]].encode(data)}"
  def scheduleOnce[T: JsonCodec](
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant],
  ): Unit =
    log += s"once|${name.value}|${summon[JsonCodec[T]].encode(data)}"
  def get(): Option[JobDetails] =
    log += s"get|${name.value}"
    None
  def delete(): Unit = ???
