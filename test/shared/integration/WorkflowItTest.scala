package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.{AddingWorkflow, CounterState, GatedWorkflow, IncrRequest}
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import unsafeExceptions.canThrowAny

/** [[WorkflowCapability]] integration suite — a SINGLE cross-platform file: start workflows, wait for completion, raise
  * external events, query status and purge, against the workflow runtime hosted in-process by the union server and
  * backed by the sidecar's scheduler service. Bring-up comes from `ServerDaprItSuite` (one implementation per
  * platform).
  *
  * The first `start` retries until the server's `WorkflowRuntime` has registered with the sidecar. [[AddingWorkflow]]
  * doubles its input via the derived activity; [[GatedWorkflow]] covers the raiseEvent path (tripling the event
  * payload, to be distinguishable from the doubling).
  */
@scala.caps.assumeSafe
class WorkflowItTest extends FunSuite, ServerDaprItSuite:

  private val addingWorkflow = WorkflowName(classOf[AddingWorkflow].getSimpleName)
  private val gatedWorkflow = WorkflowName(classOf[GatedWorkflow].getSimpleName)

  test("workflow: start with no input returns a non-empty instanceId"):
    withDapr:
      DaprCapability.workflow {
        val id = retrying("workflow runtime registered")(WorkflowCapability.start(addingWorkflow))
        assert(id.value.nonEmpty, "instanceId should be non-empty")
      }

  test("workflow: start + waitForCompletion returns the activity-doubled output"):
    withDapr:
      DaprCapability.workflow {
        val id = retrying("workflow runtime registered")(WorkflowCapability.start(addingWorkflow, IncrRequest(5)))
        val snap = WorkflowCapability
          .waitForCompletion(id, 60.seconds)
          .getOrElse(fail("workflow did not complete within 60s"))
        assertEquals(snap.status, WorkflowStatus.Completed)
        val output = snap.serializedOutput.getOrElse(fail("completed workflow should have output"))
        assertEquals(output.decodeOrThrow[CounterState], CounterState(10)) // AddActivities.add doubles: 5 * 2
      }

  test("workflow: startWithId uses the provided instanceId"):
    withDapr:
      DaprCapability.workflow {
        val customId = WorkflowInstanceId(ItNames.fresh("it-wf"))
        val returned = retrying("workflow runtime registered")(WorkflowCapability.startWithId(addingWorkflow, customId))
        assertEquals(returned, customId)
      }

  test("workflow: getStatus for an unknown id returns None"):
    withDapr:
      DaprCapability.workflow {
        // touch the runtime first so a None below means "unknown id", not "runtime not ready".
        retrying("workflow runtime registered")(WorkflowCapability.start(addingWorkflow))
        assertEquals(WorkflowCapability.getStatus(WorkflowInstanceId(ItNames.fresh("does-not-exist"))), None)
      }

  test("workflow: purge after completion returns true and getStatus returns None"):
    withDapr:
      DaprCapability.workflow {
        val id = retrying("workflow runtime registered")(WorkflowCapability.start(addingWorkflow, IncrRequest(3)))
        WorkflowCapability.waitForCompletion(id, 60.seconds).getOrElse(fail("workflow did not complete before purge"))
        assert(WorkflowCapability.purge(id), "purge should return true for a completed workflow")
        assertEquals(WorkflowCapability.getStatus(id), None)
      }

  test("workflow: raiseEvent releases a gated workflow and the payload reaches it"):
    withDapr:
      DaprCapability.workflow {
        val id = WorkflowInstanceId(ItNames.fresh("it-gated"))
        val returned = retrying("workflow runtime registered")(WorkflowCapability.startWithId(gatedWorkflow, id))
        assertEquals(returned, id)
        // Wait until the instance is parked on the external event before raising it (events raised earlier are
        // buffered by the runtime, but asserting Running makes the test deterministic).
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
