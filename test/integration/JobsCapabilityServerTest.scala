package dapr4s.test.integration

import dapr4s.*
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
  * Like [[ActorCapabilityServerTest]], the app server is started first and exposed to Docker so the sidecar can deliver
  * the scheduled job back to `/job/<name>`. A test schedules a one-shot job through the client API and then polls until
  * the [[JobRoute]] handler has recorded the delivered payload.
  */
@scala.caps.assumeSafe
class JobsCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase:

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
    val deadline = System.currentTimeMillis() + maxMs
    while System.currentTimeMillis() < deadline do
      val v = delivered.get(jobName)
      if v != null then return v
      Thread.sleep(250)
    throw RuntimeException(s"Job '$jobName' was never delivered within ${maxMs}ms")

  test("jobs: scheduleOnce fires the job back to the matching JobRoute"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.jobs {
          JobsCapability.scheduleOnce(
            JobName("welcome-email"),
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
          JobsCapability.schedule(
            JobName("daily-digest"),
            "digest-payload",
            JobSchedule.Every(scala.concurrent.duration.DurationInt(3).seconds),
          )
          val details = JobsCapability.get(JobName("daily-digest"))
          assert(details.isDefined, "expected a stored job definition")
          assertEquals(details.flatMap(_.data).map(_.decodeOrThrow[String]), Some("digest-payload"))
          // Clean up so the recurring job does not keep firing after the test.
          JobsCapability.delete(JobName("daily-digest"))
        }
    }
