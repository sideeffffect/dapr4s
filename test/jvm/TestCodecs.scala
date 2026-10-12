//> using target.platform "jvm"
package dapr4s

import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import scala.jdk.CollectionConverters.*
import unsafeExceptions.canThrowAny

// Test-only JsonCodec instances for primitive and collection types.
// These are NOT part of the library's public API.  Production code must supply
// its own instances; these exist only so tests can exercise the library without
// bringing in a specific JSON library as a compile-scoped dependency.
//
// Placed in `package dapr4s` so that `import dapr4s.*` (present in every test
// file) brings them into the implicit scope automatically.
//
// WHY @assumeSafe on every given: the anonymous JsonCodec instances must carry an
// empty capture set so safe-mode test code can summon them freely; they close over
// nothing but the shared Jackson ObjectMapper (a pure-by-contract serializer), so
// trusting them is sound.

private val testMapper = new ObjectMapper()

@scala.caps.assumeSafe
given JsonCodec[String] with
  def encode(value: String): String = testMapper.writeValueAsString(value)
  def decode(json: String | Null): Either[JsonDecodeException, String] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isTextual then Right(node.asText().nn)
        else Left(JsonDecodeException(s"expected JSON string, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Int] with
  def encode(value: Int): String = testMapper.writeValueAsString(value)
  def decode(json: String | Null): Either[JsonDecodeException, Int] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isNumber then Right(node.asInt())
        else Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Long] with
  def encode(value: Long): String = testMapper.writeValueAsString(value)
  def decode(json: String | Null): Either[JsonDecodeException, Long] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isNumber then Right(node.asLong())
        else Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Boolean] with
  def encode(value: Boolean): String = testMapper.writeValueAsString(value)
  def decode(json: String | Null): Either[JsonDecodeException, Boolean] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isBoolean then Right(node.asBoolean())
        else Left(JsonDecodeException(s"expected JSON boolean, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Double] with
  def encode(value: Double): String = testMapper.writeValueAsString(value)
  def decode(json: String | Null): Either[JsonDecodeException, Double] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isNumber then Right(node.asDouble())
        else Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Float] with
  def encode(value: Float): String = testMapper.writeValueAsString(value)
  def decode(json: String | Null): Either[JsonDecodeException, Float] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isNumber then Right(node.asDouble().toFloat)
        else Left(JsonDecodeException(s"expected JSON number, got: $json"))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given JsonCodec[Unit] with
  def encode(value: Unit): String = "null"
  def decode(json: String | Null): Either[JsonDecodeException, Unit] = Right(())

@scala.caps.assumeSafe
given JsonCodec[SecretValue] with
  def encode(value: SecretValue): String = testMapper.writeValueAsString(value.value)
  def decode(json: String | Null): Either[JsonDecodeException, SecretValue] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if node.isTextual then Right(SecretValue(node.asText().nn))
        else Left(JsonDecodeException(s"expected JSON string, got: $json"))
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
        val node = testMapper.readTree(json)
        if !node.isObject then Left(JsonDecodeException(s"expected JSON object, got: $json"))
        else
          val kCodec = summon[JsonCodec[K]]
          val vCodec = summon[JsonCodec[V]]
          val entries = node.properties().asScala.toList
          entries.foldRight(Right(Map.empty): Either[JsonDecodeException, Map[K, V]]):
            case (entry, Right(acc)) =>
              for
                k <- kCodec.decode(s""""${entry.getKey}"""")
                v <- vCodec.decode(testMapper.writeValueAsString(entry.getValue))
              yield acc + (k -> v)
            case (_, left) => left
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))

@scala.caps.assumeSafe
given [T: JsonCodec]: JsonCodec[List[T]] with
  def encode(value: List[T]): String =
    value.map(summon[JsonCodec[T]].encode).mkString("[", ",", "]")
  def decode(json: String | Null): Either[JsonDecodeException, List[T]] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try
        val node = testMapper.readTree(json)
        if !node.isArray then Left(JsonDecodeException(s"expected JSON array, got: $json"))
        else
          val codec = summon[JsonCodec[T]]
          node
            .asInstanceOf[ArrayNode]
            .elements()
            .asScala
            .toList
            .foldRight(Right(Nil): Either[JsonDecodeException, List[T]]):
              case (elem, Right(acc)) =>
                codec.decode(testMapper.writeValueAsString(elem)).map(_ :: acc)
              case (_, left) => left
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))
