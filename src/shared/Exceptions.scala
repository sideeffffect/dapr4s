package dapr4s

import dapr4s.state.*

/** Thrown by [[StateCapability.saveWithETag]] and [[StateCapability.deleteWithETag]] when the provided ETag does not
  * match the current server-side value, indicating a concurrent modification.
  *
  * Callers should re-read the key to get the current value and ETag, then retry the operation.
  */
@scala.caps.assumeSafe
final class ETagMismatchException(key: StateStoreKey, etag: ETag)
    extends Exception(s"ETag mismatch for key '${key.value}' (provided: ${etag.value})")

/** Thrown by [[JsonCodec.decodeOrThrow]] and internally by capability implementations when a JSON payload cannot be
  * parsed into the expected type.
  *
  * Indicates either a malformed JSON string or a type mismatch between the payload and the target Scala type.
  */
@scala.caps.assumeSafe
final class JsonDecodeException(message: String, cause: Exception | Null = null) extends Exception(message, cause)
