package dapr.safe

/** Typeclass for JSON serialisation/deserialisation.
  *
  * Invariants (guaranteed by all well-behaved instances):
  *   - `encode` is total (never throws)
  *   - `decode` returns `Left(errorMessage)` on failure (never throws)
  *   - `decode(null)` returns `Left("null input")`
  *   - roundtrip: `decode(encode(v)) == Right(v)`
  */
trait JsonCodec[T]:
  def encode(value: T): String
  def decode(json: String): Either[String, T]

object JsonCodec:

  /** Convenience: decode and throw [[dapr.safe.DaprException]] on failure. */
  def decodeOrThrow[T](json: String)(using codec: JsonCodec[T]): T =
    codec.decode(json) match
      case Right(v) => v
      case Left(err) => throw DaprException(s"JSON decode error: $err")

  // -------------------------------------------------------------------------
  // Primitive instances
  // -------------------------------------------------------------------------

  given JsonCodec[String] with
    def encode(value: String): String =
      upickle.default.write(value)
    def decode(json: String): Either[String, String] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[String](json))
      catch case e: Exception => Left(e.getMessage)

  given JsonCodec[Int] with
    def encode(value: Int): String =
      upickle.default.write(value)
    def decode(json: String): Either[String, Int] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[Int](json))
      catch case e: Exception => Left(e.getMessage)

  given JsonCodec[Long] with
    def encode(value: Long): String =
      upickle.default.write(value)
    def decode(json: String): Either[String, Long] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[Long](json))
      catch case e: Exception => Left(e.getMessage)

  given JsonCodec[Float] with
    def encode(value: Float): String =
      upickle.default.write(value)
    def decode(json: String): Either[String, Float] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[Float](json))
      catch case e: Exception => Left(e.getMessage)

  given JsonCodec[Boolean] with
    def encode(value: Boolean): String =
      upickle.default.write(value)
    def decode(json: String): Either[String, Boolean] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[Boolean](json))
      catch case e: Exception => Left(e.getMessage)

  given JsonCodec[Double] with
    def encode(value: Double): String =
      upickle.default.write(value)
    def decode(json: String): Either[String, Double] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[Double](json))
      catch case e: Exception => Left(e.getMessage)

  // -------------------------------------------------------------------------
  // Collection instances
  // -------------------------------------------------------------------------

  given [T: JsonCodec]: JsonCodec[Option[T]] with
    def encode(value: Option[T]): String =
      value match
        case None    => "null"
        case Some(v) => summon[JsonCodec[T]].encode(v)
    def decode(json: String): Either[String, Option[T]] =
      if json == null || json == "null" then Right(None)
      else summon[JsonCodec[T]].decode(json).map(Some(_))

  given [T: JsonCodec]: JsonCodec[List[T]] with
    def encode(value: List[T]): String =
      val codec = summon[JsonCodec[T]]
      value.map(codec.encode).mkString("[", ",", "]")
    def decode(json: String): Either[String, List[T]] =
      if json == null then return Left("null input")
      try
        // Use upickle to parse the JSON array, then decode each element
        val arr = upickle.default.read[ujson.Value](json)
        arr match
          case ujson.Arr(items) =>
            val codec = summon[JsonCodec[T]]
            val results = items.map(item => codec.decode(ujson.write(item)))
            val errors = results.collect { case Left(e) => e }
            if errors.nonEmpty then Left(errors.mkString("; "))
            else Right(results.collect { case Right(v) => v }.toList)
          case _ => Left(s"Expected JSON array, got: $json")
      catch case e: Exception => Left(e.getMessage)

  // -------------------------------------------------------------------------
  // Generic instance via upickle ReadWriter derivation
  // -------------------------------------------------------------------------

  given [T](using rw: upickle.default.ReadWriter[T]): JsonCodec[T] with
    def encode(value: T): String = upickle.default.write(value)
    def decode(json: String): Either[String, T] =
      if json == null then return Left("null input")
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(e.getMessage)
