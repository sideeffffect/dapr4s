//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.concurrent.duration.FiniteDuration

@scala.caps.assumeSafe
private[internal] final class LockCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: LockStoreName,
) extends LockCapability:

  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration): Boolean =
    // expiry.toSeconds truncates to whole seconds — the same rounding the JVM impl applies
    // (`expiry.toSeconds.toInt` into LockRequest).
    val response = JsAwait.await(
      scope.client.lock.lock(storeName.value, resourceId.value, lockOwner.value, expiry.toSeconds.toDouble),
    )
    // ScalablyTyped types `success` as a required Boolean; the equality test (rather than trusting the typed read)
    // keeps the JVM twin's null-handling (`.toOption.exists(_.booleanValue())`): an absent/undefined field at
    // runtime compares unequal to true and yields false instead of an undefined-as-Boolean read.
    (response.success: Any) == true

  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    val response = JsAwait.await(scope.client.lock.unlock(storeName.value, resourceId.value, lockOwner.value))
    // Numeric LockStatus → UnlockStatus, copied from the JVM impl's UnlockResponseStatus mapping:
    // Success (0) → Success, LockDoesNotExist (1) → LockNotFound, and LockBelongsToOthers (2) maps
    // to InternalError exactly like the JVM maps LOCK_BELONG_TO_OTHERS (dapr4s's UnlockStatus has
    // no owner-mismatch case); InternalError (3), unknown values, and an absent status (JVM: null
    // response) all collapse to InternalError.
    //
    // The numeric values are PINNED here (`_statusToLockStatus`, implementation/Client/HTTPClient/
    // lock.js; enum LockStatus, types/lock/UnlockResponse.ts) instead of read off the SDK enum
    // object like the other status mappings: LockStatus is not re-exported from the `@dapr/dapr`
    // root, and its deep module (`@dapr/dapr/types/lock/UnlockResponse`) is unresolvable under
    // Node ESM (ScalablyTyped emits extension-less specifiers; the package has no `exports` map —
    // see the note in InvokeCapabilityImpl), so referencing the enum object would crash at module
    // load. The type-tested read keeps unknown/absent shapes on the InternalError path.
    // Every JS number pattern-matches as Double on Scala.js (see JsInterop.isFalsyJson).
    (response.status: Any) match
      case d: Double if d == 0 => UnlockStatus.Success
      case d: Double if d == 1 => UnlockStatus.LockNotFound
      case _                   => UnlockStatus.InternalError
