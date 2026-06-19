package dapr4s.lock

import dapr4s.*

import language.experimental.safe

/** Caller-chosen identifier for the owner of a distributed lock.
  *
  * Must not be empty. Passed to [[LockCapability.tryLock]] when acquiring a lock and to [[LockCapability.unlock]] when
  * releasing it. The lock store uses this value to verify ownership: only the owner that acquired the lock can release
  * it.
  */
opaque type LockOwner = String
object LockOwner:
  def apply(s: String): LockOwner =
    require(s.nonEmpty, "LockOwner must not be empty")
    s
  extension (o: LockOwner) def value: String = o
