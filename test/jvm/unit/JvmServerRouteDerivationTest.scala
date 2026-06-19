//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import dapr4s.derivation.*
import munit.FunSuite
import scala.collection.mutable

/** The jobs-contract slice of [[ServerRouteDerivationTest]] — JVM-only because the `ReportJobs` scheduling contract
  * mentions `JobsCapability`/`JobSchedule`/`JobDetails`, which exist only on the JVM (the Dapr JS SDK has no jobs API).
  * `JobRoutes.deriveChecked` itself is shared, but a checked derivation needs the contract trait, and that trait cannot
  * compile on Scala.js. The plain `JobRoutes.derive` case (no contract) stays in the shared suite, dispatching to the
  * shared [[ReportJobHandlers]].
  */
trait ReportJobs:
  def nightly(spec: Req, schedule: JobSchedule)(using AccessJobsCapability, JsonCodec[Req]): Unit
  @name("nightly") def status()(using AccessJobsCapability): Option[JobDetails] // getter: not a trigger

@scala.caps.assumeSafe
class JvmServerRouteDerivationTest extends FunSuite:

  test("JobRoutes.deriveChecked: checks scheduling contract by job name, skips getters"):
    val rec = new Recorder { val log = mutable.ListBuffer.empty[String] }
    given Recorder = rec
    // ReportJobs schedules "nightly" (payload Req) and has a "nightly" getter (skipped).
    val routes = JobRoutes.deriveChecked[ReportJobs, ReportJobHandlers.type]
    assertEquals(routes.map(_.name.value), List("nightly"))
    routes.head.rawHandler.asInstanceOf[Any => Any](Req(2))
    assertEquals(rec.log.toList, List("job2"))
