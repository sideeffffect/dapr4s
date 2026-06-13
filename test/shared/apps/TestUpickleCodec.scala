package dapr4s.test.integration.apps

import dapr4s.*
import unsafeExceptions.canThrowAny

def upickleCodec[T: upickle.default.ReadWriter]: JsonCodec[T] = new JsonCodec[T]:
  def encode(value: T): String = upickle.default.write(value)
  def decode(json: String | Null): Either[JsonDecodeException, T] =
    if json == null then Left(JsonDecodeException("null input"))
    else
      try Right(upickle.default.read[T](json))
      catch case e: Exception => Left(JsonDecodeException(e.getMessage, e))
