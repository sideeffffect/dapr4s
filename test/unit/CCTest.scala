package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite
import language.experimental.saferExceptions

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
      intercept[DaprException]:
        runSafe:
          JsonCodec.decodeOrThrow[Int]("\"not-an-int\"")
