package dapr4s

import unsafeExceptions.canThrowAny
import scala.util.control.NonFatal

/** Typeclass for JSON serialisation/deserialisation.
  *
  * Invariants (guaranteed by all well-behaved instances):
  *   - `encode` is total (never throws)
  *   - `decode` returns `Left(JsonDecodeException)` on failure (never throws)
  *   - `decode(null)` returns `Left(JsonDecodeException("null input"))`
  *   - roundtrip: `decode(encode(v)) == Right(v)`
  *
  * Marked `@scala.caps.assumeSafe` because the underlying upickle library is not compiled in safe mode. Library authors
  * vouch for the safety contract.
  */
@scala.caps.assumeSafe
trait JsonCodec[T]:
  def encode(value: T): String
  def decode(json: String | Null): Either[JsonDecodeException, T]

@scala.caps.assumeSafe
object JsonCodec:

  /** Convenience: decode and throw [[JsonDecodeException]] on failure. */
  def decodeOrThrow[T](json: String | Null)(using codec: JsonCodec[T]): T =
    codec.decode(json) match
      case Right(v)  => v
      case Left(err) => throw err

  // -------------------------------------------------------------------------
  // Primitive instances
  //
  // Each primitive needs its own given rather than deferring to the generic
  // ReadWriter-based instance below.  The generic instance exposes ReadWriter
  // as an implicit parameter that callers must satisfy; in safe mode (import
  // language.experimental.safe) upickle's ReadWriter is not @assumedSafe, so
  // the compiler rejects it.  These specific givens call upickle directly
  // inside the @assumeSafe boundary, hiding the dependency from callers.
  // -------------------------------------------------------------------------

  private def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
    def encode(value: T): String = upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, T] =
      if json == null then Left(JsonDecodeException("null input"))
      else
        try Right(upickle.default.read[T](json.nn))
        catch case NonFatal(e: Exception) => Left(JsonDecodeException(e.getMessage, e))

  given JsonCodec[String] = upickleCodec[String]
  given JsonCodec[Int] = upickleCodec[Int]
  given JsonCodec[Long] = upickleCodec[Long]
  given JsonCodec[Float] = upickleCodec[Float]
  given JsonCodec[Boolean] = upickleCodec[Boolean]
  given JsonCodec[Double] = upickleCodec[Double]

  given JsonCodec[Unit] with
    def encode(value: Unit): String = "null"
    def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

  // -------------------------------------------------------------------------
  // Collection instances
  // -------------------------------------------------------------------------

  given [T: JsonCodec]: JsonCodec[Option[T]] with
    def encode(value: Option[T]): String =
      value match
        case None    => "null"
        case Some(v) => summon[JsonCodec[T]].encode(v)
    def decode(json: String | Null): Either[JsonDecodeException, Option[T]] =
      if json == null || json == "null" then Right(None)
      else summon[JsonCodec[T]].decode(json).map(Some(_))

  given [T: JsonCodec]: JsonCodec[List[T]] with
    def encode(value: List[T]): String =
      val codec = summon[JsonCodec[T]]
      value.map(codec.encode).mkString("[", ",", "]")
    def decode(json: String | Null): Either[JsonDecodeException, List[T]] =
      if json == null then Left(JsonDecodeException("null input"))
      else
        try
          // Use upickle to parse the JSON array, then decode each element
          val arr = upickle.default.read[ujson.Value](json.nn)
          arr match
            case ujson.Arr(items) =>
              val codec = summon[JsonCodec[T]]
              val results = items.map(item => codec.decode(ujson.write(item)))
              val errors = results.collect { case Left(e) => e }
              if errors.nonEmpty then Left(JsonDecodeException(errors.map(_.getMessage).mkString("; ")))
              else Right(results.collect { case Right(v) => v }.toList)
            case _ => Left(JsonDecodeException(s"Expected JSON array, got: $json"))
        catch case NonFatal(e: Exception) => Left(JsonDecodeException(e.getMessage, e))

  // -------------------------------------------------------------------------
  // Generic instance via upickle ReadWriter derivation
  // -------------------------------------------------------------------------

  given [T](using rw: upickle.default.ReadWriter[T]): JsonCodec[T] with
    def encode(value: T): String = upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, T] =
      if json == null then Left(JsonDecodeException("null input"))
      else
        try Right(upickle.default.read[T](json.nn))
        catch case NonFatal(e: Exception) => Left(JsonDecodeException(e.getMessage, e))
