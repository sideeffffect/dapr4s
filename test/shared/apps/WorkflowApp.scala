package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*, dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.derivation.*
import language.experimental.safe

// ---------------------------------------------------------------------------
// Simple workflow that doubles a number via a derived activity
// ---------------------------------------------------------------------------

/** Activity implementation as a plain class — reified to registrable activities by
  * [[dapr4s.derivation.WorkflowActivities.derive]]; no `extends WorkflowActivity` / `execute` boilerplate.
  */
class AddActivities:
  def add(input: IncrRequest)(using DaprCapability): CounterState =
    CounterState(input.amount * 2) // doubles the input for test verification

/** Typed caller facade derived from [[AddActivities]]; `add` schedules the activity and returns a `Task`. */
trait AddCalls:
  def add(input: IncrRequest)(using ctx: WorkflowContext): Task[CounterState]^{ctx}
lazy val AddCalls: AddCalls = WorkflowActivityCalls.deriveChecked[AddCalls, AddActivities]

class AddingWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val input  = WorkflowContext.getInput[IncrRequest].getOrElse(IncrRequest(0))
    val acts   = AddCalls
    val result = acts.add(input).await()
    WorkflowContext.complete(result)

// ---------------------------------------------------------------------------
// DaprApp with workflow + derived activities registered
// ---------------------------------------------------------------------------

object WorkflowApp:
  def apply(): DaprApp = DaprApp(
    workflows = List(new AddingWorkflow),
    activities = WorkflowActivities.derive[AddActivities],
  )
