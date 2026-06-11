package dapr4s

import language.experimental.safe

/** Identifier for the resource being protected by a distributed lock.
  *
  * Must not be empty. Scoped to the lock store component named in the enclosing [[LockCapability]]. Two callers
  * competing for the same `LockResourceId` on the same store will coordinate via the lock; different stores or IDs are
  * independent.
  */
opaque type LockResourceId = String
object LockResourceId:
  def apply(s: String): LockResourceId =
    require(s.nonEmpty, "LockResourceId must not be empty")
    s
  extension (id: LockResourceId) def value: String = id
