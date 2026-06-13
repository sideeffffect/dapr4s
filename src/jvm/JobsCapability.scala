//> using target.platform "jvm"
package dapr4s

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
trait JobsCapability extends scala.caps.ExclusiveCapability:

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
      name: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  ): Unit

  /** Schedule a one-shot job that fires once at `dueTime`. */
  def scheduleOnce[T: JsonCodec](
      name: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  ): Unit

  /** Fetch a job's stored definition. Returns `None` if no job with that name exists. */
  def get(name: JobName): Option[JobDetails]

  /** Delete a scheduled job (no-op if it does not exist). */
  def delete(name: JobName): Unit

/** Companion-object API for [[JobsCapability]].
  *
  * Forwards to the `JobsCapability` in the enclosing `using` context:
  * {{{
  *   def scheduleReminder(id: String)(using JobsCapability): Unit =
  *     JobsCapability.schedule(JobName(s"reminder-$id"), id, JobSchedule.Every(1.hour))
  * }}}
  */
@scala.caps.assumeSafe
object JobsCapability:
  def schedule[T: JsonCodec](
      name: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobsCapability): Unit =
    cap.schedule(name, data, schedule, dueTime, repeats, ttl)
  def scheduleOnce[T: JsonCodec](
      name: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobsCapability): Unit =
    cap.scheduleOnce(name, data, dueTime, ttl)
  def get(name: JobName)(using cap: JobsCapability): Option[JobDetails] =
    cap.get(name)
  def delete(name: JobName)(using cap: JobsCapability): Unit =
    cap.delete(name)
