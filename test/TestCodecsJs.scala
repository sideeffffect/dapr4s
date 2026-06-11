//> using target.platform "scala-js"
package dapr4s

// Scala.js twin of TestCodecs.scala (which is JVM-only because it uses Jackson, a transitive
// dependency of the Dapr Java SDK). Provides the same test-only JsonCodec given instances,
// implemented over ujson (from upickle, a cross-platform test dependency), so the shared unit
// tests compile and run unchanged on the JS platform.
//
// Placed in `package dapr4s` so that `import dapr4s.*` (present in every test file) brings
// them into the implicit scope automatically — exactly like the JVM twin.
//
// Note on Long: ujson backs numbers with Double, so longs beyond 2^53 lose precision here.
// That is inherent to JavaScript's JSON number model, not a codec bug; tests avoid such values.

@scala.caps.assumeSafe
given JsonCodec[String] with
  def encode(value: String): String = ujson.write(ujson.Str(value))
  def decode(json: String | Null): Either[JsonDecodeException, String] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Str(s) => Right(s)
          case _            => Left(JsonDecodeException(s"expected JSON string, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Int] with
  def encode(value: Int): String = ujson.write(ujson.Num(value.toDouble))
  def decode(json: String | Null): Either[JsonDecodeException, Int] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Num(d) => Right(d.toInt)
          case _            => Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Long] with
  def encode(value: Long): String = value.toString
  def decode(json: String | Null): Either[JsonDecodeException, Long] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Num(d) => Right(d.toLong)
          case _            => Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Boolean] with
  def encode(value: Boolean): String = ujson.write(ujson.Bool(value))
  def decode(json: String | Null): Either[JsonDecodeException, Boolean] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Bool(b) => Right(b)
          case _             => Left(JsonDecodeException(s"expected JSON boolean, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Double] with
  def encode(value: Double): String = ujson.write(ujson.Num(value))
  def decode(json: String | Null): Either[JsonDecodeException, Double] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Num(d) => Right(d)
          case _            => Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Float] with
  def encode(value: Float): String = ujson.write(ujson.Num(value.toDouble))
  def decode(json: String | Null): Either[JsonDecodeException, Float] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Num(d) => Right(d.toFloat)
          case _            => Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Unit] with
  def encode(value: Unit): String = "null"
  def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

@scala.caps.assumeSafe
given JsonCodec[SecretValue] with
  def encode(value: SecretValue): String = ujson.write(ujson.Str(value.value))
  def decode(json: String | Null): Either[JsonDecodeException, SecretValue] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Str(s) => Right(SecretValue(s))
          case _            => Left(JsonDecodeException(s"expected JSON string, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given [K: JsonCodec, V: JsonCodec]: JsonCodec[Map[K, V]] with
  def encode(value: Map[K, V]): String =
    value
      .map: (k, v) =>
        val keyStr = summon[JsonCodec[K]].encode(k)
        val valStr = summon[JsonCodec[V]].encode(v)
        s"$keyStr:$valStr"
      .mkString("{", ",", "}")
  def decode(json: String | Null): Either[JsonDecodeException, Map[K, V]] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Obj(entries) =>
            val kCodec = summon[JsonCodec[K]]
            val vCodec = summon[JsonCodec[V]]
            entries.toList.foldRight(Right(Map.empty): Either[JsonDecodeException, Map[K, V]]):
              case ((key, value), Right(acc)) =>
                for
                  k <- kCodec.decode(ujson.write(ujson.Str(key)))
                  v <- vCodec.decode(ujson.write(value))
                yield acc + (k -> v)
              case (_, left) => left
          case _ => Left(JsonDecodeException(s"expected JSON object, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given [T: JsonCodec]: JsonCodec[List[T]] with
  def encode(value: List[T]): String =
    value.map(summon[JsonCodec[T]].encode).mkString("[", ",", "]")
  def decode(json: String | Null): Either[JsonDecodeException, List[T]] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        ujson.read(json) match
          case ujson.Arr(items) =>
            val codec = summon[JsonCodec[T]]
            items.toList.foldRight(Right(Nil): Either[JsonDecodeException, List[T]]):
              case (elem, Right(acc)) => codec.decode(ujson.write(elem)).map(_ :: acc)
              case (_, left)          => left
          case _ => Left(JsonDecodeException(s"expected JSON array, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))
