package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given
import language.experimental.safe

// ---------------------------------------------------------------------------
// Simple workflow that adds two numbers via an activity
// ---------------------------------------------------------------------------

class AddActivity extends WorkflowActivity[IncrRequest, CounterState]:
  def execute(input: IncrRequest)(using DaprCapability): CounterState =
    CounterState(input.amount * 2) // doubles the input for test verification

class AddingWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val input = WorkflowContext.getInput[IncrRequest].getOrElse(IncrRequest(0))
    val task = WorkflowContext.callActivity[AddActivity](input)
    val result = task.await()
    WorkflowContext.complete(result)

// ---------------------------------------------------------------------------
// DaprApp with workflow + activity registered
// ---------------------------------------------------------------------------

object WorkflowApp:
  def apply(): DaprApp = DaprApp(
    workflows = List(new AddingWorkflow),
    activities = List(new AddActivity),
  )
