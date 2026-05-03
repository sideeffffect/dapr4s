package dapr.safe.test.integration.apps

import dapr.safe.*
import language.experimental.safe

// ---------------------------------------------------------------------------
// Simple workflow that adds two numbers via an activity
// ---------------------------------------------------------------------------

class AddActivity extends DaprActivity[IncrRequest, CounterState]:
  def execute(input: IncrRequest): CounterState =
    CounterState(input.amount * 2) // doubles the input for test verification

class AddingWorkflow extends DaprWorkflow:
  def run(ctx: WorkflowContext): Unit =
    val input = ctx.getInput[IncrRequest].getOrElse(IncrRequest(0))
    val task = ctx.callActivity(classOf[AddActivity], input)
    val result = task.await()
    ctx.complete(result)

// ---------------------------------------------------------------------------
// DaprApp with workflow + activity registered
// ---------------------------------------------------------------------------

object WorkflowApp:
  def daprApp: DaprApp = DaprApp(
    workflows = List(new AddingWorkflow),
    activities = List(new AddActivity),
  )
