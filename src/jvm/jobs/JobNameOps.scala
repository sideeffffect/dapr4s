//> using target.platform "jvm"
package dapr4s.jobs

import dapr4s.*

/** Handle-style sugar for [[JobName]]: act on the named job through the jobs capability in scope —
  * `JobName("digest").get`, `name.delete()`.
  *
  * Each method requires the **narrowest** tier that carries the operation (`get` needs only
  * [[JobsCapability.ReadOnly]]), so the sugar never re-demands authority the caller was not granted. Equivalent to the
  * universal calls (`JobsCapability.get(name)`) and to `jobs.instance(name).get()`.
  *
  * WHY a top-level extension in `src/jvm/` rather than in the [[JobName]] companion: `JobName` is shared (the inbound
  * [[JobRoute]] side is cross-platform) but [[JobsCapability]] exists on the JVM only, so these methods cannot live in
  * the shared companion. They are in scope with `import dapr4s.jobs.*`.
  */
extension (name: JobName)
  /** Fetch the job's stored definition; `None` if it does not exist. */
  def get(using cap: JobsCapability.ReadOnly): Option[JobDetails] =
    cap.get(name)

  /** Schedule this job on a recurring [[JobSchedule]]; see [[JobsCapability.schedule]]. */
  def schedule[T: JsonCodec](
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant] = None,
      repeats: Option[Int] = None,
      ttl: Option[java.time.Instant] = None,
  )(using cap: JobsCapability): Unit =
    cap.schedule(name, data, schedule, dueTime, repeats, ttl)

  /** Schedule this job to fire once at `dueTime`; see [[JobsCapability.scheduleOnce]]. */
  def scheduleOnce[T: JsonCodec](data: T, dueTime: java.time.Instant, ttl: Option[java.time.Instant] = None)(using
      cap: JobsCapability,
  ): Unit =
    cap.scheduleOnce(name, data, dueTime, ttl)

  /** Delete this job (no-op if it does not exist). */
  def delete()(using cap: JobsCapability): Unit =
    cap.delete(name)
