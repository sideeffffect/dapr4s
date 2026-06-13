//> using target.platform "jvm"
package dapr4s

// WHAT: no `import language.experimental.safe` here, although the file these models were split
// out of (src/shared/Models.scala) is in safe mode.
// WHY: with the safe import in these jvm-tagged model files, the 3.10.0-RC1 nightly's capture
// checker fails on *unrelated* files — the `@scala.caps.assumeSafe` enums in src/shared/optypes
// (StateConcurrency, StateConsistency) error on their synthesized `values` method
// ("dapr4s.X.$values.clone(): fresh cannot flow into capture set {}"). Empirically bisected: the
// error appears/disappears deterministically with the safe import in ConversationModels.scala
// (and is order-fragile for this file), so both split-out model files stay out of safe mode.
// WHY SAFE: safe mode only adds checking; these are pure data definitions (enums/case classes)
// with no capabilities, no escape hatches, and no side effects — there is nothing for safe mode
// to catch here.
// WHERE TO LOOK: src/shared/Models.scala (the safe-mode original these were split from);
// AGENTS.md "Escape hatches" section.
import scala.concurrent.duration.FiniteDuration

// JVM-only: these models belong to the JVM-only JobsCapability (the Dapr JS SDK has no jobs
// API — see DaprCapabilityPlatform). The job *trigger* side (JobRoute, JobName) stays shared.

/** When a [[JobsCapability.schedule]] job should run.
  *
  * The Dapr scheduler accepts a cron expression, a fixed period, or one of the named shortcuts. Construct via the cases
  * directly (e.g. `JobSchedule.Cron("0 30 * * * *")`, `JobSchedule.Every(5.seconds)`, `JobSchedule.Daily`).
  */
enum JobSchedule:
  /** A standard cron expression (Dapr uses a 6-field, seconds-first format). */
  case Cron(expression: String)

  /** Run repeatedly with a fixed period between runs. */
  case Every(period: FiniteDuration)

  case Daily
  case Hourly
  case Weekly
  case Monthly
  case Yearly

/** A job's stored definition, as returned by [[JobsCapability.get]].
  *
  * @param name
  *   The job's [[JobName]].
  * @param data
  *   The job's payload as stored by the scheduler (the JSON the job was scheduled with), if any.
  * @param scheduleExpression
  *   The raw schedule expression the scheduler holds (e.g. `"@every 5s"`, `"@daily"`, or a cron string), if the job is
  *   recurring.
  * @param dueTime
  *   The one-shot due time, if the job was scheduled to run once at a specific instant.
  * @param repeats
  *   The remaining number of times the job will run, if a repeat count was set.
  * @param ttl
  *   The instant after which the job expires, if a TTL was set.
  */
final case class JobDetails(
    name: JobName,
    data: Option[SerializedJson],
    scheduleExpression: Option[String],
    dueTime: Option[java.time.Instant],
    repeats: Option[Int],
    ttl: Option[java.time.Instant],
)
