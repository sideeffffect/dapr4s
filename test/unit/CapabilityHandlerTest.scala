package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.test.integration.apps.*
import munit.FunSuite

/** Structural smoke tests for DaprApp-based handlers that need no external infrastructure. */
@scala.caps.assumeSafe
class CapabilityHandlerTest extends FunSuite:

  test("unit: WorkflowApp DaprApp has non-empty workflows and activities"):
    val app = WorkflowApp.daprApp
    assert(app.workflows.nonEmpty, "expected non-empty workflows")
    assert(app.activities.nonEmpty, "expected non-empty activities")
