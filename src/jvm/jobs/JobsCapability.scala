//> using target.platform "jvm"
package dapr4s.jobs

import dapr4s.*

/** Universal capability for scheduling DAPR jobs (client side): reaches **any** job, so every operation names its
  * [[JobName]].
  *
  * Obtained via [[DaprCapability.jobs]]. The capability family has four interfaces, all in this companion:
  *
  *   - [[JobsCapability]] — any job, full authority (this trait; adds `schedule`, `scheduleOnce`, `delete`)
  *   - [[JobsCapability.ReadOnly]] — any job, read only (`get`)
  *   - [[JobsCapability.Instance]] — one job, full authority
  *   - [[JobsCapability.Instance.ReadOnly]] — one job, read only
  *
  * Narrow by job with [[apply]] (`jobs(jobName)`), and by authority by upcasting to the `ReadOnly` super-trait. The two
  * narrowings commute: `apply` on a [[JobsCapability.ReadOnly]] yields a [[JobsCapability.Instance.ReadOnly]].
  *
  * '''JVM-only:''' the Dapr JS SDK has no jobs API, so this capability (and [[DaprCapability.jobs]], via
  * `DaprCapabilityPlatform`) exists only on the JVM — on Scala.js using it is a compile error. The inbound counterpart
  * [[JobRoute]] '''is''' cross-platform: answering job triggers needs no SDK support, only an HTTP route.
  *
  * '''Dual:''' [[JobRoute]] is the inbound counterpart. Scheduling is decoupled from handling: a scheduled job fires as
  * an inbound trigger the sidecar POSTs back to the app, handled by a `JobRoute` for the same [[JobName]] registered in
  * the [[DaprApp]]. (Derivation binds the two through one trait: `Jobs.derive` ↔ `JobRoutes.deriveChecked`.)
  */
@scala.caps.assumeSafe
trait JobsCapability extends JobsCapability.ReadOnly:
  /** Narrow to the named job, keeping full authority. */
  override def instance(jobName: JobName): JobsCapability.Instance^{this}

  /** Schedule a recurring job; see [[JobsCapability.Instance.schedule]]. */
  def schedule[T: JsonCodec](
      jobName: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  ): Unit

  /** Schedule a one-shot job that fires once at `dueTime`. */
  def scheduleOnce[T: JsonCodec](
      jobName: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  ): Unit

  /** Delete the named scheduled job (no-op if it does not exist). */
  def delete(jobName: JobName): Unit

/** The [[JobsCapability]] family: the read-only universal view, the job-bound instance views, and the companion-object
  * forwarders for each.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def scheduleReminder(id: String)(using JobsCapability.Instance): Unit =
  *     JobsCapability.Instance.schedule(id, JobSchedule.Every(1.hour))
  * }}}
  */
@scala.caps.assumeSafe
object JobsCapability:

  /** Read-only view of [[JobsCapability]]: reaches any job, but only reads its definition. */
  @scala.caps.assumeSafe
  trait ReadOnly extends scala.caps.ExclusiveCapability:
    /** Narrow to the named job, keeping read-only authority. */
    def instance(jobName: JobName): Instance.ReadOnly^{this}

    /** Fetch the named job's stored definition. Returns `None` if no job with this name exists. */
    def get(jobName: JobName): Option[JobDetails]

  /** Forwarders for [[JobsCapability.ReadOnly]]. */
  @scala.caps.assumeSafe
  object ReadOnly:
    /** Narrow the read-only capability in scope to one job and return it. */
    def instance(jobName: JobName)(using cap: ReadOnly): Instance.ReadOnly^{cap} =
      cap.instance(jobName)
    def get(jobName: JobName)(using cap: ReadOnly): Option[JobDetails] =
      cap.get(jobName)

  /** Capability bound to one named job, full authority. Obtained via `jobs(jobName)`. */
  @scala.caps.assumeSafe
  trait Instance extends Instance.ReadOnly:
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

    /** Delete this scheduled job (no-op if it does not exist). */
    def delete(): Unit

  /** [[JobsCapability.Instance]]'s read-only view, and the forwarders for [[JobsCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:

    /** Capability bound to one named job, read only. */
    @scala.caps.assumeSafe
    trait ReadOnly extends scala.caps.ExclusiveCapability:
      /** The job this capability is bound to. */
      val jobName: JobName

      /** Fetch this job's stored definition. Returns `None` if no job with this name exists. */
      def get(): Option[JobDetails]

    /** Forwarders for [[JobsCapability.Instance.ReadOnly]]. */
    @scala.caps.assumeSafe
    object ReadOnly:
      def get()(using cap: ReadOnly): Option[JobDetails] =
        cap.get()

    def get()(using cap: Instance): Option[JobDetails] =
      cap.get()
    def schedule[T: JsonCodec](
        data: T,
        schedule: JobSchedule,
        dueTime: Option[java.time.Instant] = None,
        repeats: Option[Int] = None,
        ttl: Option[java.time.Instant] = None,
    )(using cap: Instance): Unit =
      cap.schedule(data, schedule, dueTime, repeats, ttl)
    def scheduleOnce[T: JsonCodec](
        data: T,
        dueTime: java.time.Instant,
        ttl: Option[java.time.Instant] = None,
    )(using cap: Instance): Unit =
      cap.scheduleOnce(data, dueTime, ttl)
    def delete()(using cap: Instance): Unit =
      cap.delete()

  /** Narrow the universal capability in scope to one job and return it. */
  def instance(jobName: JobName)(using cap: JobsCapability): Instance^{cap} =
    cap.instance(jobName)
  def get(jobName: JobName)(using cap: JobsCapability): Option[JobDetails] =
    cap.get(jobName)
  def schedule[T: JsonCodec](
      jobName: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobsCapability): Unit =
    cap.schedule(jobName, data, schedule, dueTime, repeats, ttl)
  def scheduleOnce[T: JsonCodec](
      jobName: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobsCapability): Unit =
    cap.scheduleOnce(jobName, data, dueTime, ttl)
  def delete(jobName: JobName)(using cap: JobsCapability): Unit =
    cap.delete(jobName)
