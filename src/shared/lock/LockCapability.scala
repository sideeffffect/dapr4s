package dapr4s.lock

import dapr4s.*
import scala.concurrent.duration.FiniteDuration

/** Universal capability for DAPR distributed locking: reaches **any** lock store, so every operation names its store.
  *
  * Obtained via [[DaprCapability.lock]]. Narrow to one store with [[apply]] (`lock(storeName)`), which yields a
  * [[LockCapability.Instance]].
  */
@scala.caps.assumeSafe
trait LockCapability extends scala.caps.ExclusiveCapability:
  /** Narrow to the named lock store. */
  def apply(storeName: LockStoreName): LockCapability.Instance^{this}

  /** Try to acquire a lock in the named store; see [[LockCapability.Instance.tryLock]]. */
  def tryLock(
      storeName: LockStoreName,
      resourceId: LockResourceId,
      lockOwner: LockOwner,
      expiry: FiniteDuration,
  ): Boolean

  /** Release a previously acquired lock in the named store; see [[LockCapability.Instance.unlock]]. */
  def unlock(storeName: LockStoreName, resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus

/** The [[LockCapability]] family: the store-bound [[LockCapability.Instance]] and the companion-object forwarders for
  * both interfaces.
  *
  * Every forwarder takes its capability from the enclosing `using` context, so callers never name it:
  * {{{
  *   def withLock(resource: LockResourceId, owner: LockOwner)(using LockCapability.Instance): Boolean =
  *     if LockCapability.Instance.tryLock(resource, owner, expiry = 30.seconds) then
  *       try doWork(); true
  *       finally LockCapability.Instance.unlock(resource, owner)
  *     else false
  * }}}
  */
@scala.caps.assumeSafe
object LockCapability:

  /** Capability bound to one named lock store. Obtained via `lock(storeName)`. */
  @scala.caps.assumeSafe
  trait Instance extends scala.caps.ExclusiveCapability:
    /** The lock store this capability is bound to. */
    val storeName: LockStoreName

    /** Try to acquire a lock. Returns true if acquired, false if already held. */
    def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration): Boolean

    /** Release a previously acquired lock. */
    def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus

  /** Forwarders for [[LockCapability.Instance]]. */
  @scala.caps.assumeSafe
  object Instance:
    def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration)(using
        cap: Instance,
    ): Boolean =
      cap.tryLock(resourceId, lockOwner, expiry)
    def unlock(resourceId: LockResourceId, lockOwner: LockOwner)(using cap: Instance): UnlockStatus =
      cap.unlock(resourceId, lockOwner)

  /** Narrow the universal capability in scope to one store and make it available to `body`. */
  def instance(storeName: LockStoreName)[T](body: Instance ?=> T)(using cap: LockCapability): T =
    // WHAT/WHY/SAFE: same capture-set erasure as the DaprCapability transformers — the narrowed capability is
    // `Instance^{cap}`, but a context function parameter cannot mention `cap`; it is only used inside `body`, which
    // cannot leak it (pure context function). See DaprCapability's companion.
    body(using cap.apply(storeName).asInstanceOf[Instance])
  def tryLock(storeName: LockStoreName, resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration)(using
      cap: LockCapability,
  ): Boolean =
    cap.tryLock(storeName, resourceId, lockOwner, expiry)
  def unlock(storeName: LockStoreName, resourceId: LockResourceId, lockOwner: LockOwner)(using
      cap: LockCapability,
  ): UnlockStatus =
    cap.unlock(storeName, resourceId, lockOwner)
