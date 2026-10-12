package dapr4s.test.unit

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import munit.FunSuite

/** Structural smoke tests for DaprApp-based handlers that need no external infrastructure. */
@scala.caps.assumeSafe
class CapabilityHandlerTest extends FunSuite:

  test("unit: WorkflowApp DaprApp has non-empty workflows and activities"):
    val app = WorkflowApp()
    assert(app.workflows.nonEmpty, "expected non-empty workflows")
    assert(app.activities.nonEmpty, "expected non-empty activities")
