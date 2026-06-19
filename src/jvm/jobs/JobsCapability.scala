//> using target.platform "jvm"
package dapr4s.jobs

import dapr4s.*

/** Capability for scheduling DAPR jobs (client side).
  *
  * '''JVM-only:''' the Dapr JS SDK has no jobs API, so this capability (and [[DaprCapability.jobs]], via
  * `DaprCapabilityPlatform`) exists only on the JVM — on Scala.js using it is a compile error. The inbound counterpart
  * [[JobRoute]] '''is''' cross-platform: answering job triggers needs no SDK support, only an HTTP route.
  *
  * '''Dual:''' [[JobRoute]] is the inbound counterpart. Scheduling is decoupled from handling: a scheduled job fires as
  * an inbound trigger the sidecar POSTs back to the app, handled by a `JobRoute` for the same [[JobName]] registered in
  * the [[DaprApp]]. Acquired via [[DaprCapability.jobs]]. (Derivation binds the two through one trait: `Jobs.derive` ↔
  * `JobRoutes.deriveChecked`.)
  */
@scala.caps.assumeSafe
trait AccessJobsCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[JobCapability]] bound to the named job. */
  def apply(name: JobName): JobCapability^{this}

@scala.caps.assumeSafe
trait JobCapability extends scala.caps.ExclusiveCapability:
  val jobName: JobName

  /** Schedule a recurring job. The `data` payload is delivered to the matching [[JobRoute]] each time the job fires.
    *
    * @param dueTime
    *   optional first-run time; if omitted the schedule determines the first run
    * @param repeats
    *   optional cap on the number of times the job runs
    * @param ttl
    *   optional expiry instant after which the job is removed
    */
  def schedule[T: JsonCodec](
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  ): Unit

  /** Schedule a one-shot job that fires once at `dueTime`. */
  def scheduleOnce[T: JsonCodec](
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  ): Unit

  /** Fetch this job's stored definition. Returns `None` if no job with this name exists. */
  def get(): Option[JobDetails]

  /** Delete this scheduled job (no-op if it does not exist). */
  def delete(): Unit

/** Companion-object API for [[JobCapability]].
  *
  * Forwards to the `JobCapability` in the enclosing `using` context (already bound to a job name):
  * {{{
  *   def scheduleReminder(id: String)(using JobCapability): Unit =
  *     JobCapability.schedule(id, JobSchedule.Every(1.hour))
  * }}}
  */
@scala.caps.assumeSafe
object JobCapability:
  def schedule[T: JsonCodec](
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobCapability): Unit =
    cap.schedule(data, schedule, dueTime, repeats, ttl)
  def scheduleOnce[T: JsonCodec](
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobCapability): Unit =
    cap.scheduleOnce(data, dueTime, ttl)
  def get()(using cap: JobCapability): Option[JobDetails] =
    cap.get()
  def delete()(using cap: JobCapability): Unit =
    cap.delete()
