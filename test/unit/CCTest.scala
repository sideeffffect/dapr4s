package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite

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
  //   DaprRuntime.run body: (DaprCapability, CanThrow[Exception]) ?=> T
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
    val exclaim: String => String   = s => s + "!"
    val pipeline                    = normalise andThen exclaim
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
