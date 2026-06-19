//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import dapr4s.given
import dapr4s.internal.DaprAppServer
import dapr4s.test.unit.DaprServerTestBase
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.util.concurrent.ConcurrentHashMap

/** Round-trip tests for [[JobsCapability]] / [[JobRoute]] against a real Dapr sidecar and its scheduler service (the
  * testcontainers `DaprContainer` starts a scheduler container automatically).
  *
  * Like the server-delivery suites, the app server is started and exposed to Docker so the sidecar can deliver the
  * scheduled job back to `/job/<name>`. A test schedules a one-shot job through the client API and then polls until the
  * [[JobRoute]] handler has recorded the delivered payload. (JVM-only: the Dapr JS SDK has no jobs API, so there is no
  * shared twin.)
  */
@scala.caps.assumeSafe
class JobsCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase with JvmItPolling:

  type Containers = DaprTestContainer

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  // jobName -> delivered payload, populated by the JobRoute handlers when the sidecar fires the job.
  private val delivered = new ConcurrentHashMap[String, String]()

  private var appServerThread: Option[Thread] = None

  override def afterAll(): Unit =
    super.afterAll()
    appServerThread.foreach { t => t.interrupt(); t.join(2000) }

  private def jobsApp: DaprApp =
    DaprApp(jobs =
      List(
        JobRoute[String](JobName("welcome-email")) { payload => delivered.put("welcome-email", payload) },
        JobRoute[String](JobName("daily-digest")) { payload => delivered.put("daily-digest", payload) },
      ),
    )

  override def startContainers(): DaprTestContainer =
    org.testcontainers.Testcontainers.exposeHostPorts(appPort)

    val server = new DaprAppServer(jobsApp)
    appServerThread = Some(
      Thread.ofVirtual().start(() => server.startAndBlock(appPort, TestDapr.placeholderCapability)),
    )
    waitForPort(appPort, 5000)

    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(org.testcontainers.containers.Network.SHARED)
        .withAppName("jobs-server-test")
        .withAppPort(appPort)
        .withAppChannelAddress("host.testcontainers.internal"),
    )
    c.start()
    c

  private def waitForDelivery(jobName: String, maxMs: Int = 30000): String =
    eventually(s"job '$jobName' delivered", timeoutMs = maxMs, intervalMs = 250)(Option(delivered.get(jobName)))

  test("jobs: scheduleOnce fires the job back to the matching JobRoute"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.jobs {
          val job = summon[AccessJobsCapability](JobName("welcome-email"))
          job.scheduleOnce(
            "user-42",
            java.time.Instant.now().plusSeconds(2),
          )
        }
      assertEquals(waitForDelivery("welcome-email"), "user-42")
    }

  test("jobs: get returns the stored definition of a scheduled recurring job"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.jobs {
          val job = summon[AccessJobsCapability](JobName("daily-digest"))
          job.schedule(
            "digest-payload",
            JobSchedule.Every(scala.concurrent.duration.DurationInt(3).seconds),
          )
          val details = job.get()
          assert(details.isDefined, "expected a stored job definition")
          assertEquals(details.flatMap(_.data).map(_.decodeOrThrow[String]), Some("digest-payload"))
          // Clean up so the recurring job does not keep firing after the test.
          job.delete()
        }
    }
