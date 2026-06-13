package dapr4s

import unsafeExceptions.canThrowAny

/** Typeclass for JSON serialisation/deserialisation used at the boundary between dapr4s and user-supplied domain types.
  *
  * Invariants (guaranteed by all well-behaved instances):
  *   - `encode` is total (never throws)
  *   - `decode` returns `Left(JsonDecodeException)` on failure (never throws)
  *   - `decode(null)` returns `Left(JsonDecodeException("null input"))`
  *   - roundtrip: `decode(encode(v)) == Right(v)`
  *
  * The library provides no built-in instances. Users supply `JsonCodec[T]` for their domain types (and for any
  * primitives they pass through capabilities) using whichever JSON library they prefer.
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
