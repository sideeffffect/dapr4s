//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.{AddingWorkflow, CounterState, IncrRequest}
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[WorkflowCapability]] against the workflow runtime hosted by the JS test server ([[dapr4s.internal.WorkflowHost]] +
  * the AsyncGenerator coroutine bridge), backed by the harness sidecar's scheduler service — the Scala.js twin of
  * [[WorkflowCapabilityServerTest]].
  *
  * The first `start` retries until the server's `WorkflowRuntime` has registered with the sidecar (the JVM twin's
  * `waitForWorkflowRuntime` poll). [[AddingWorkflow]] doubles its input via the derived activity; [[GatedWorkflow]]
  * covers the raiseEvent path (tripling the event payload).
  */
@scala.caps.assumeSafe
class WorkflowJsIntegrationTest extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  private val addingWorkflow = WorkflowName(classOf[AddingWorkflow].getSimpleName)
  private val gatedWorkflow = WorkflowName(classOf[GatedWorkflow].getSimpleName)

  test("workflow: start + waitForCompletion returns the activity-doubled output"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.workflow {
          val id = retryUntilSuccess("workflow runtime registered") {
            WorkflowCapability.start(addingWorkflow, IncrRequest(5))
          }
          assert(id.value.nonEmpty, "instanceId should be non-empty")
          val snap = WorkflowCapability
            .waitForCompletion(id, 60.seconds)
            .getOrElse(fail("workflow did not complete within 60s"))
          assertEquals(snap.status, WorkflowStatus.Completed)
          val output = snap.serializedOutput.getOrElse(fail("completed workflow should have output"))
          assertEquals(output.decodeOrThrow[CounterState], CounterState(10)) // AddActivities.add doubles: 5 * 2
        }
    }.toFuture

  test("workflow: raiseEvent releases a gated workflow and the payload reaches it"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.workflow {
          val id = WorkflowInstanceId(s"js-it-gated-${uniqueId()}")
          val returned = retryUntilSuccess("workflow runtime registered") {
            WorkflowCapability.startWithId(gatedWorkflow, id)
          }
          assertEquals(returned, id)
          // Wait until the instance is parked on the external event before raising it (events raised
          // earlier are buffered by the runtime, but asserting Running makes the test deterministic).
          val running = eventually(s"gated workflow $id running") {
            WorkflowCapability.getStatus(id).filter(_.status == WorkflowStatus.Running)
          }
          assertEquals(running.status, WorkflowStatus.Running)
          WorkflowCapability.raiseEvent(id, EventName("go"), IncrRequest(4))
          val snap = WorkflowCapability
            .waitForCompletion(id, 60.seconds)
            .getOrElse(fail("gated workflow did not complete within 60s"))
          assertEquals(snap.status, WorkflowStatus.Completed)
          val output = snap.serializedOutput.getOrElse(fail("completed workflow should have output"))
          assertEquals(output.decodeOrThrow[CounterState], CounterState(12)) // GatedWorkflow triples: 4 * 3
        }
    }.toFuture
