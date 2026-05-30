package dapr4s

import language.experimental.safe

// JsonCodec[Option[T]] combinator for tests. Kept in a safe-mode file so that
// safe-mode test files can use it without an @assumedSafe annotation.
given [T: JsonCodec]: JsonCodec[Option[T]] with
  def encode(value: Option[T]): String =
    value match
      case None    => "null"
      case Some(v) => summon[JsonCodec[T]].encode(v)
  def decode(json: String | Null): Either[JsonDecodeException, Option[T]] =
    if json == null || json == "null" then Right(None)
    else summon[JsonCodec[T]].decode(json).map(Some(_))
