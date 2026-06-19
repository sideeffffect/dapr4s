package dapr4s.lock

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.FiniteDuration


/** Accessor (rung 2) for lock stores: an "any store" handle obtained argument-less via [[DaprCapability.lock]], whose
  * [[apply]] narrows to a [[LockCapability]] bound to one store.
  */
@scala.caps.assumeSafe
trait AccessLockCapability extends scala.caps.ExclusiveCapability:
  /** Obtain a [[LockCapability]] for the named lock store. */
  def apply(storeName: LockStoreName): LockCapability^{this}

/** Capability for DAPR distributed locking against a named lock store. */
@scala.caps.assumeSafe
trait LockCapability extends scala.caps.ExclusiveCapability:
  val storeName: LockStoreName

  /** Try to acquire a lock. Returns true if acquired, false if already held. */
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration): Boolean

  /** Release a previously acquired lock. */
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus

/** Companion-object API for [[LockCapability]].
  *
  * Forwards to the `LockCapability` in the enclosing `using` context:
  * {{{
  *   def withLock(resource: LockResourceId, owner: LockOwner)(using LockCapability): Boolean =
  *     if LockCapability.tryLock(resource, owner, expiry = 30.seconds) then
  *       try doWork(); true
  *       finally LockCapability.unlock(resource, owner)
  *     else false
  * }}}
  */
@scala.caps.assumeSafe
object LockCapability:
  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration)(using
      cap: LockCapability,
  ): Boolean =
    cap.tryLock(resourceId, lockOwner, expiry)
  def unlock(resourceId: LockResourceId, lockOwner: LockOwner)(using
      cap: LockCapability,
  ): UnlockStatus =
    cap.unlock(resourceId, lockOwner)

