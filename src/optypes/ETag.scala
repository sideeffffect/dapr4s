package dapr4s

import language.experimental.safe

/** Server-assigned version token for optimistic concurrency control.
  *
  * Returned by [[StateCapability.getWithETag]] as part of a [[StateEntry]]. Pass the same value to
  * [[StateCapability.saveWithETag]] or [[StateCapability.deleteWithETag]] to perform a conditional write; the operation
  * fails with [[ETagMismatchException]] if another writer has modified the key in the meantime. May be empty when the
  * key does not yet exist.
  */
opaque type ETag = String
object ETag:
  def apply(s: String): ETag = s
  extension (n: ETag) def value: String = n
