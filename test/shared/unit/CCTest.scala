package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import munit.FunSuite

// Compile-time fixtures for the Task-capture tests below.  IntActivity uses the test-only
// JsonCodec[Int] (package dapr4s, imported via `dapr4s.given`).
class IntActivity extends WorkflowActivity[Int, Int]:
  def execute(input: Int)(using DaprCapability): Int = input

// Fan-out: two Tasks are alive at the same time, both capturing the same WorkflowContext.
// The mere fact that this class compiles proves that holding several `Task[O]^{ctx}` values
// simultaneously does not trip capture checking (named captures, not the universal `^`).
class FanOutWorkflow extends Workflow:
  def run(using WorkflowContext): Unit =
    val t1 = WorkflowContext.callActivity[IntActivity](1)
    val t2 = WorkflowContext.callActivity[IntActivity](2)
    val _ = t1.await()
    val _ = t2.await()

/** Capture-checking behavioural tests.
  *
  * Verifies that pure-function composition works correctly and that the ExclusiveCapability hierarchy compiles — all
  * capability types that extend ExclusiveCapability are accepted at the appropriate type positions. The JsonCodec tests
  * have been moved to [[JsonCodecTest]].
  */
@scala.caps.assumeSafe
class CCTest extends FunSuite:

  // ---------------------------------------------------------------------------
  // pureFunctions
  //
  // With -language:experimental.pureFunctions, A => B is a *pure* function
  // type — the compiler rejects lambdas whose body captures a CC-tracked
  // capability.  The key guarantee for this library:
  //   Dapr(config).run body: (DaprCapability, CanThrow[Exception]) ?=> T
  // is now a pure context function.  The body may use only the two context
  // parameters it is explicitly given; it cannot silently close over an
  // external DaprCapability or CanThrow capability.
  //
  // This guarantee is enforced at compile time by the library building cleanly
  // under -Wconf:any:error with all three experimental flags active.
  // ---------------------------------------------------------------------------

  test("pureFunctions: pure lambda with no external captures composes correctly"):
    // Demonstrates that pure A => B functions work and compose cleanly.
    val normalise: String => String = s => s.trim.toLowerCase
    val exclaim: String => String = s => s + "!"
    val pipeline = normalise andThen exclaim
    assertEquals(pipeline("  Hello World  "), "hello world!")

  // ---------------------------------------------------------------------------
  // ExclusiveCapability hierarchy — compile-time structural checks
  //
  // Each assertion verifies that the type hierarchy is correct at compile time.
  // These tests document the ExclusiveCapability guarantee: DaprCapability,
  // ActorContext, and WorkflowContext are exclusive root capabilities.
  // ---------------------------------------------------------------------------

  test("ExclusiveCapability: DaprCapability is an exclusive capability"):
    assert(classOf[scala.caps.ExclusiveCapability].isAssignableFrom(classOf[DaprCapability]))

  test("ExclusiveCapability: ActorContext is an exclusive capability"):
    assert(classOf[scala.caps.ExclusiveCapability].isAssignableFrom(classOf[ActorContext]))

  test("ExclusiveCapability: WorkflowContext is an exclusive capability"):
    // WorkflowContext extends ExclusiveCapability — verified by the project compiling with
    // -language:experimental.captureChecking and -Wconf:any:error.
    assert(classOf[scala.caps.ExclusiveCapability].isAssignableFrom(classOf[WorkflowContext]))

  // ---------------------------------------------------------------------------
  // Task capture — a Task captures the WorkflowContext (`Task[O]^{ctx}`), so it
  // cannot escape `Workflow.run`.  FanOutWorkflow (above) is the positive proof
  // that multiple live Tasks compile; the pair below proves the escape is
  // rejected, with a positive control so the failure can't be a spurious import error.
  // ---------------------------------------------------------------------------

  test("Task capture: awaiting a Task inline inside run compiles"):
    val inline = scala.compiletime.testing.typeChecks("""
      import dapr4s.*
      import dapr4s.given
      import dapr4s.test.unit.IntActivity
      new Workflow:
        def run(using WorkflowContext): Unit =
          val t = WorkflowContext.callActivity[IntActivity](1)
          val _ = t.await()
    """)
    assert(inline, "awaiting a Task inline inside run must compile")

  test("Task capture: a Task cannot be smuggled into an outer var (escape is rejected)"):
    val escapes = scala.compiletime.testing.typeChecks("""
      import dapr4s.*
      import dapr4s.given
      import dapr4s.test.unit.IntActivity
      var escaped: Task[Int] = null
      new Workflow:
        def run(using WorkflowContext): Unit =
          escaped = WorkflowContext.callActivity[IntActivity](1)
    """)
    assert(!escapes, "a Task captures the WorkflowContext and must not escape run via an outer var")
