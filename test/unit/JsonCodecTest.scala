package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite
import language.experimental.saferExceptions

@scala.caps.assumeSafe
class JsonCodecTest extends FunSuite:

  // -------------------------------------------------------------------------
  // Primitive instances
  // -------------------------------------------------------------------------

  test("JsonCodec[String] roundtrip"):
    val codec = summon[JsonCodec[String]]
    assertEquals(codec.decode(codec.encode("hello")), Right("hello"))

  test("JsonCodec[String] encode produces JSON string literal"):
    val codec = summon[JsonCodec[String]]
    assertEquals(codec.encode("world"), "\"world\"")

  test("JsonCodec[Int] roundtrip"):
    val codec = summon[JsonCodec[Int]]
    assertEquals(codec.decode(codec.encode(42)), Right(42))

  test("JsonCodec[Long] roundtrip"):
    val codec = summon[JsonCodec[Long]]
    assertEquals(codec.decode(codec.encode(Long.MaxValue)), Right(Long.MaxValue))

  test("JsonCodec[Boolean] roundtrip true"):
    val codec = summon[JsonCodec[Boolean]]
    assertEquals(codec.decode(codec.encode(true)), Right(true))

  test("JsonCodec[Boolean] roundtrip false"):
    val codec = summon[JsonCodec[Boolean]]
    assertEquals(codec.decode(codec.encode(false)), Right(false))

  test("JsonCodec[Double] roundtrip"):
    val codec = summon[JsonCodec[Double]]
    assertEquals(codec.decode(codec.encode(3.14)), Right(3.14))

  // -------------------------------------------------------------------------
  // Decode errors — return Left(JsonDecodeException)
  // -------------------------------------------------------------------------

  test("JsonCodec[Int] decode returns Left on non-integer JSON"):
    val codec = summon[JsonCodec[Int]]
    assert(codec.decode("\"not a number\"").isLeft)

  test("JsonCodec[Int] decode returns Left containing JsonDecodeException"):
    val codec = summon[JsonCodec[Int]]
    codec.decode("\"not a number\"") match
      case Left(e)  => assert(e.isInstanceOf[JsonDecodeException])
      case Right(_) => fail("Expected Left")

  test("JsonCodec[Int] decode returns Left on empty string"):
    val codec = summon[JsonCodec[Int]]
    assert(codec.decode("").isLeft)

  test("JsonCodec[Boolean] decode returns Left on garbage"):
    val codec = summon[JsonCodec[Boolean]]
    assert(codec.decode("maybe").isLeft)

  // -------------------------------------------------------------------------
  // upickle ReadWriter derivation
  // -------------------------------------------------------------------------

  case class Point(x: Int, y: Int) derives upickle.default.ReadWriter

  test("JsonCodec via ReadWriter roundtrip"):
    val codec = summon[JsonCodec[Point]]
    val p = Point(3, 7)
    assertEquals(codec.decode(codec.encode(p)), Right(p))

  test("JsonCodec via ReadWriter decode error returns Left"):
    val codec = summon[JsonCodec[Point]]
    assert(codec.decode("{\"x\": 1}").isLeft) // missing y field

  // -------------------------------------------------------------------------
  // decodeOrThrow helper — needs CanThrow created inside the test body lambda
  // -------------------------------------------------------------------------

  test("decodeOrThrow returns value on success"):
    // Use try/catch to avoid needing CanThrow capability — we expect no exception here
    val v =
      try JsonCodec.decodeOrThrow[Int]("99")
      catch case e: Exception => fail(s"unexpected: $e")
    assertEquals(v, 99)

  test("decodeOrThrow throws JsonDecodeException on failure"):
    var exOpt: Exception | Null = null
    try JsonCodec.decodeOrThrow[Int]("\"not-an-int\"")
    catch case e: Exception => exOpt = e
    assert(exOpt != null && exOpt.isInstanceOf[JsonDecodeException])

  test("decodeOrThrow throws DaprException (subtype) on failure"):
    var exOpt: Exception | Null = null
    try JsonCodec.decodeOrThrow[Int]("\"not-an-int\"")
    catch case e: Exception => exOpt = e
    assert(exOpt != null && exOpt.isInstanceOf[DaprException])

  // -------------------------------------------------------------------------
  // Null input guard — returns Left(JsonDecodeException("null input"))
  // -------------------------------------------------------------------------

  test("JsonCodec[String] decode(null) returns Left with JsonDecodeException"):
    val result = summon[JsonCodec[String]].decode(null)
    assert(result.isLeft)
    result match
      case Left(e) =>
        assert(e.isInstanceOf[JsonDecodeException])
        assertEquals(e.getMessage, "null input")
      case Right(_) => fail("Expected Left")

  test("JsonCodec[Int] decode(null) returns Left with JsonDecodeException"):
    val result = summon[JsonCodec[Int]].decode(null)
    assert(result.isLeft)
    result match
      case Left(e)  => assertEquals(e.getMessage, "null input")
      case Right(_) => fail("Expected Left")

  // -------------------------------------------------------------------------
  // Option instances
  // -------------------------------------------------------------------------

  test("JsonCodec[Option[String]] roundtrip Some"):
    val codec = summon[JsonCodec[Option[String]]]
    assertEquals(codec.decode(codec.encode(Some("hello"))), Right(Some("hello")))

  test("JsonCodec[Option[String]] roundtrip None"):
    val codec = summon[JsonCodec[Option[String]]]
    assertEquals(codec.encode(None), "null")
    assertEquals(codec.decode("null"), Right(None))

  test("JsonCodec[Option[Int]] roundtrip Some(0)"):
    val codec = summon[JsonCodec[Option[Int]]]
    assertEquals(codec.decode(codec.encode(Some(0))), Right(Some(0)))

  test("JsonCodec[Option[String]] decode(null) returns Right(None)"):
    val codec = summon[JsonCodec[Option[String]]]
    assertEquals(codec.decode(null), Right(None))

  // -------------------------------------------------------------------------
  // List instances
  // -------------------------------------------------------------------------

  test("JsonCodec[List[String]] roundtrip"):
    val codec = summon[JsonCodec[List[String]]]
    val xs = List("a", "b", "c")
    assertEquals(codec.decode(codec.encode(xs)), Right(xs))

  test("JsonCodec[List[Int]] roundtrip"):
    val codec = summon[JsonCodec[List[Int]]]
    val xs = List(1, 2, 3)
    assertEquals(codec.decode(codec.encode(xs)), Right(xs))

  test("JsonCodec[List[String]] empty list roundtrip"):
    val codec = summon[JsonCodec[List[String]]]
    assertEquals(codec.decode(codec.encode(List.empty[String])), Right(List.empty[String]))

  test("JsonCodec[List[Int]] decode returns Left on non-array"):
    val codec = summon[JsonCodec[List[Int]]]
    assert(codec.decode("42").isLeft)

  test("JsonCodec[List[Int]] decode(null) returns Left with JsonDecodeException"):
    val result = summon[JsonCodec[List[Int]]].decode(null)
    assert(result.isLeft)
    result match
      case Left(e)  => assertEquals(e.getMessage, "null input")
      case Right(_) => fail("Expected Left")
