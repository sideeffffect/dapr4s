package dapr.safe

import language.experimental.saferExceptions

@scala.caps.assumeSafe
final class ETagMismatchException(key: StateKey, etag: ETag)
    extends Exception(s"ETag mismatch for key '${key.value}' (provided: ${etag.value})")

@scala.caps.assumeSafe
final class JsonDecodeException(message: String, cause: Exception | Null = null) extends Exception(message, cause)
