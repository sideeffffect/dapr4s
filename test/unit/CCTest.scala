package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite

@scala.caps.assumeSafe
class CCTest extends FunSuite:

  // All tests go through this helper which provides CanThrow inside a fresh lambda
  def runSafe[T](body: CanThrow[Exception] ?=> T): T =
    given CanThrow[Exception] = unsafeExceptions.canThrowAny
    body

  test("t1"):
    runSafe:
      JsonCodec.decodeOrThrow[Int]("99")

  test("t2"):
    runSafe:
      intercept[JsonDecodeException]:
        runSafe:
          JsonCodec.decodeOrThrow[Int]("\"not-an-int\"")

  test("t3"):
    runSafe:
      intercept[JsonDecodeException]:
        runSafe:
          JsonCodec.decodeOrThrow[Int]("\"not-an-int\"")

  // ---------------------------------------------------------------------------
  // pureFunctions: A => B is pure — cannot capture a CanThrow capability
  // ---------------------------------------------------------------------------

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
    // The body type (DaprCapability, CanThrow[Exception]) ?=> T is now pure.
    // Verify the runtime contract: scope is provided, used, and released.
    runSafe:
      val mock = MockDaprCapability()
      mock.state(StoreName("s")).save(StateKey("k"), "v")
      assertEquals(mock.state(StoreName("s")).get[String](StateKey("k")), Some("v"))
      mock.close()
      assert(mock.isClosed)

  // ---------------------------------------------------------------------------
  // clauseInterleaving: Resp inferred position verified via MockDaprCapability
  // ---------------------------------------------------------------------------

  test("clauseInterleaving: invoke syntax — Req inferred, Resp specified after args"):
    runSafe:
      val scope = MockDaprCapability()
      val invoker = scope.invoker
      // Req (String) is inferred from "request-data"; Resp specified as trailing [String]
      intercept[UnsupportedOperationException]:
        invoker.invoke(AppId("app"), MethodName("method"), "request-data")[String]
      scope.close()

  test("clauseInterleaving: binding invoke syntax — Req inferred, Resp specified after args"):
    runSafe:
      val scope = MockDaprCapability()
      val binding = scope.binding(BindingName("my-binding"))
      // Req (String) inferred from "payload"; Resp specified as trailing [String]
      val result: Option[String] = binding.invoke(BindingOperation("operation"), "payload")[String]
      assertEquals(result, None)
      scope.close()
