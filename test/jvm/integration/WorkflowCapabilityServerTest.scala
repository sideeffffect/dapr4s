//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.internal.DaprAppServer
import dapr4s.test.unit.DaprServerTestBase
import dapr4s.test.integration.apps.*
import io.dapr.testcontainers.DaprContainer
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny
import scala.concurrent.duration.*

/** Integration tests for [[WorkflowCapability]] backed by a real Dapr sidecar and workflow runtime.
  *
  * Setup mirrors [[ActorCapabilityServerTest]] but for workflows:
  *   1. The app server starts first on a pre-allocated port, exposing the workflow runtime to the sidecar.
  *   2. The Dapr sidecar (Testcontainers) starts with `withAppPort` + `withAppChannelAddress`; it calls the app's gRPC
  *      port to register the workflow runtime.
  *   3. After the sidecar is up, tests use [[Dapr.runWithEndpoints]] to obtain a [[WorkflowCapability]].
  *
  * [[WorkflowApp]] registers [[AddingWorkflow]] + the derived [[AddActivities]] (called through [[AddCalls]]). The
  * activity doubles its input, so starting the workflow with `IncrRequest(5)` should produce `CounterState(10)`.
  */
@scala.caps.assumeSafe
class WorkflowCapabilityServerTest extends FunSuite with TestContainersForAll with DaprServerTestBase with RedisFixture:

  type Containers = DaprTestContainer

  private val appPort: Int =
    val s = java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private var appServerThread: Option[Thread] = None

  override def afterAll(): Unit =
    super.afterAll()
    appServerThread.foreach { t => t.interrupt(); t.join(2000) }

  override def startContainers(): DaprTestContainer =
    // Make the host-side app server reachable from inside Docker containers.
    org.testcontainers.Testcontainers.exposeHostPorts(appPort)

    // Start the sidecar FIRST: the workflow runtime connects outbound to the sidecar's gRPC
    // endpoint, which Testcontainers maps to a dynamic host port. The runtime must be told that
    // port (not the default 50001), so the sidecar has to exist before we start the app server.
    val res = startRedis(org.testcontainers.containers.Network.SHARED)
    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withNetwork(org.testcontainers.containers.Network.SHARED)
        .withAppName("workflow-server-test")
        .withAppPort(appPort)
        .withAppChannelAddress("host.testcontainers.internal")
        .withComponent(res.component("statestore")),
    )
    c.start()

    // Point the workflow runtime at the sidecar's dynamic gRPC port, then start the app server.
    val wfProps = new io.dapr.config.Properties(
      java.util.Map.of(io.dapr.config.Properties.GRPC_ENDPOINT.getName, c.grpcEndpoint.toString),
    )
    val server = new DaprAppServer(WorkflowApp())
    appServerThread = Some(
      Thread
        .ofVirtual()
        .start(() => server.startAndBlock(appPort, TestDapr.placeholderCapability, workflowProperties = wfProps)),
    )
    waitForPort(appPort, 5000)

    // Give the workflow runtime time to connect to the sidecar gRPC endpoint and register.
    waitForWorkflowRuntime(c)

    c

  private val workflowName = WorkflowName(classOf[AddingWorkflow].getSimpleName)

  private def thirtySeconds: FiniteDuration = 30.seconds

  // Poll until the workflow runtime has registered with the sidecar by attempting to start a workflow
  // and retrying until we get a non-5xx response (or the attempt succeeds).
  private def waitForWorkflowRuntime(c: DaprTestContainer, maxMs: Int = 60000): Unit =
    import scala.util.boundary
    import scala.util.boundary.break
    val deadline = System.currentTimeMillis() + maxMs
    var lastMsg = ""
    var done = false
    while !done && System.currentTimeMillis() < deadline do
      try
        Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
          val wf = summon[DaprCapability].workflow
          try
            wf.start(workflowName)
            done = true // success — runtime is registered
          catch
            case e: io.dapr.exceptions.DaprException =>
              lastMsg = Option(e.getMessage).getOrElse(e.getClass.getName)
              if e.getHttpStatusCode == 500 then Thread.sleep(500)
              else done = true // non-500 means runtime is up
            case e: Exception =>
              lastMsg = Option(e.getMessage).getOrElse(e.getClass.getName)
              Thread.sleep(500)
      catch
        case e: Exception =>
          lastMsg = Option(e.getMessage).getOrElse(e.getClass.getName)
          Thread.sleep(500)
    if !done then throw RuntimeException(s"Workflow runtime did not register within ${maxMs}ms — last=$lastMsg")

  // ---- workflow start --------------------------------------------------------

  test("workflow: start with no input returns a non-empty instanceId"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val wf = summon[DaprCapability].workflow
        val id = wf.start(workflowName)
        assert(id.value.nonEmpty, "instanceId should be non-empty")
    }

  test("workflow: start with input and waitForCompletion returns doubled result"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        given wf: WorkflowCapability = summon[DaprCapability].workflow
        val id = wf.start(workflowName, IncrRequest(5))
        val snapshot = id.waitForCompletion(thirtySeconds)
        assert(snapshot.isDefined, "workflow should complete within 30 seconds")
        val snap = snapshot.get
        assertEquals(snap.status, WorkflowStatus.Completed)
        val output = snap.serializedOutput
        assert(output.isDefined, "completed workflow should have serialized output")
        val result = output.get.decodeOrThrow[CounterState]
        assertEquals(result, CounterState(10)) // AddActivities.add doubles input: 5 * 2 = 10
    }

  test("workflow: startWithId uses the provided instanceId"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        val wf = summon[DaprCapability].workflow
        val customId = WorkflowInstanceId(s"test-wf-${java.util.UUID.randomUUID()}")
        val returnedId = wf.startWithId(workflowName, customId)
        assertEquals(returnedId.value, customId.value)
    }

  test("workflow: getStatus for unknown id returns None"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        given wf: WorkflowCapability = summon[DaprCapability].workflow
        val id = WorkflowInstanceId(s"does-not-exist-${java.util.UUID.randomUUID()}")
        val status = id.getStatus
        assertEquals(status, None)
    }

  test("workflow: purge after completion returns true and getStatus returns None"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        given wf: WorkflowCapability = summon[DaprCapability].workflow
        val id = wf.start(workflowName, IncrRequest(3))
        val snapshot = id.waitForCompletion(thirtySeconds)
        assert(snapshot.isDefined, "workflow should complete before purge")
        val purged = id.purge()
        assert(purged, "purge should return true for a completed workflow")
        val statusAfterPurge = id.getStatus
        assertEquals(statusAfterPurge, None)
    }
