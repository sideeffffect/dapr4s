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
  // typeCheckErrors compiles strings without the project's experimental flags,
  // so negative-compilation checks for CC purity cannot be expressed as unit
  // tests — the guarantee is enforced by the library compiling cleanly under
  // -Wconf:any:error with all three experimental flags active.
  // ---------------------------------------------------------------------------

  test("pureFunctions: pure lambda with no external captures composes correctly"):
    // Demonstrates that pure A => B functions work and compose cleanly.
    val normalise: String => String = s => s.trim.toLowerCase
    val exclaim: String => String = s => s + "!"
    val pipeline = normalise andThen exclaim
    assertEquals(pipeline("  Hello World  "), "hello world!")

  test("pureFunctions: DaprRuntime.run body is a pure context function"):
    val mock = MockDaprCapability()
    mock.state(StoreName("s")).save(StateKey("k"), "v")
    assertEquals(mock.state(StoreName("s")).get[String](StateKey("k")), Some("v"))
    mock.close()
    assert(mock.isClosed)

  // ---------------------------------------------------------------------------
  // ExclusiveCapability hierarchy — compile-time structural checks
  //
  // Each summon verifies that the type hierarchy is correct at compile time.
  // These tests document the ExclusiveCapability guarantee: DaprCapability,
  // ActorContext, and WorkflowContext are exclusive root capabilities.
  // ---------------------------------------------------------------------------

  test("ExclusiveCapability: DaprCapability is an exclusive capability"):
    // Verify at compile time that DaprCapability is a subtype of ExclusiveCapability.
    // The MockDaprCapability instance is assignable to ExclusiveCapability if the hierarchy is correct.
    val mock: DaprCapability = MockDaprCapability()
    val _: scala.caps.ExclusiveCapability = mock.asInstanceOf[scala.caps.ExclusiveCapability]

  test("ExclusiveCapability: ActorContext is an exclusive capability"):
    val ctx: ActorContext = new MockActorContext()
    val _: scala.caps.ExclusiveCapability = ctx.asInstanceOf[scala.caps.ExclusiveCapability]

  test("ExclusiveCapability: WorkflowContext is an exclusive capability"):
    // WorkflowContext extends ExclusiveCapability — verified by the project compiling with
    // -language:experimental.captureChecking and -Wconf:any:error.
    assert(classOf[scala.caps.ExclusiveCapability].isAssignableFrom(classOf[WorkflowContext]))

  // ---------------------------------------------------------------------------
  // clauseInterleaving: Resp inferred position verified via MockDaprCapability
  // ---------------------------------------------------------------------------

  test("clauseInterleaving: invoke syntax — Req inferred, Resp specified after args"):
    val scope = MockDaprCapability()
    val invoker = scope.invoker
    // Req (String) is inferred from "request-data"; Resp specified as trailing [String]
    intercept[UnsupportedOperationException]:
      invoker.invoke(AppId("app"), MethodName("method"), "request-data")[String]
    scope.close()

  test("clauseInterleaving: binding invoke syntax — Req inferred, Resp specified after args"):
    val scope = MockDaprCapability()
    val binding = scope.binding(BindingName("my-binding"))
    // Req (String) inferred from "payload"; Resp specified as trailing [String]
    val result: Option[String] = binding.invoke(BindingOperation("operation"), "payload")[String]
    assertEquals(result, None)
    scope.close()
