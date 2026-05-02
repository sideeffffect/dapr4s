package dapr.safe

import language.experimental.saferExceptions

/** Typeclass for JSON serialisation/deserialisation.
  *
  * Invariants (guaranteed by all well-behaved instances):
  *   - `encode` is total (never throws)
  *   - `decode` returns `Left(JsonDecodeException)` on failure (never throws)
  *   - `decode(null)` returns `Left(JsonDecodeException("null input"))`
  *   - roundtrip: `decode(encode(v)) == Right(v)`
  *
  * Marked `@scala.caps.assumeSafe` because the underlying upickle library is not
  * compiled in safe mode. Library authors vouch for the safety contract.
  */
@scala.caps.assumeSafe
trait JsonCodec[T]:
  def encode(value: T): String
  def decode(json: String | Null): Either[JsonDecodeException, T]

@scala.caps.assumeSafe
object JsonCodec:

  /** Convenience: decode and throw [[JsonDecodeException]] on failure. */
  def decodeOrThrow[T](json: String | Null)(using codec: JsonCodec[T]): T throws JsonDecodeException =
    codec.decode(json) match
      case Right(v)  => v
      case Left(err) => throw err

  // -------------------------------------------------------------------------
  // Primitive instances
  // -------------------------------------------------------------------------

  given JsonCodec[String] with
    def encode(value: String): String =
      upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, String] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[String](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

  given JsonCodec[Int] with
    def encode(value: Int): String =
      upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, Int] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[Int](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

  given JsonCodec[Long] with
    def encode(value: Long): String =
      upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, Long] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[Long](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

  given JsonCodec[Float] with
    def encode(value: Float): String =
      upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, Float] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[Float](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

  given JsonCodec[Boolean] with
    def encode(value: Boolean): String =
      upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, Boolean] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[Boolean](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

  given JsonCodec[Double] with
    def encode(value: Double): String =
      upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, Double] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[Double](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

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
      if json == null then return Left(JsonDecodeException("null input"))
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
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

  // -------------------------------------------------------------------------
  // Generic instance via upickle ReadWriter derivation
  // -------------------------------------------------------------------------

  given [T](using rw: upickle.default.ReadWriter[T]): JsonCodec[T] with
    def encode(value: T): String = upickle.default.write(value)
    def decode(json: String | Null): Either[JsonDecodeException, T] =
      if json == null then return Left(JsonDecodeException("null input"))
      try Right(upickle.default.read[T](json.nn))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))
