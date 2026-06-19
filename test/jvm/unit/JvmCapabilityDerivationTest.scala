//> using target.platform "jvm"
package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import munit.FunSuite

/** The jobs slice of [[CapabilityDerivationTest]] — JVM-only because `Jobs.derive` and `JobsCapability` exist only on
  * the JVM (the Dapr JS SDK has no jobs API). Fixtures live in [[JvmCapabilityDerivationFixtures]].
  */
@scala.caps.assumeSafe
class JvmCapabilityDerivationTest extends FunSuite:

  test("Jobs: schedule, scheduleOnce, get"):
    val fake = FakeJobs()
    given AccessJobsCapability = fake
    val client = JobClient
    client.recur(Req(1), JobSchedule.Every(scala.concurrent.duration.DurationInt(5).seconds))
    client.once(Req(2), java.time.Instant.EPOCH)
    client.fetch()
    assertEquals(fake.log.toList, List("schedule|recur|1", "once|once|2", "get|recur"))
