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
      scope.client.lock.lock(storeName.value, resourceId.value, lockOwner.value, expiry.toSeconds.toInt),
    )
    // Absent `success` → false, mirroring the JVM's `.toOption.exists(_.booleanValue())` null handling.
    response.success.contains(true)

  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    val response = JsAwait.await(scope.client.lock.unlock(storeName.value, resourceId.value, lockOwner.value))
    // Numeric LockStatus → UnlockStatus, copied from the JVM impl's UnlockResponseStatus mapping:
    // Success (0) → Success, LockDoesNotExist (1) → LockNotFound, and LockBelongsToOthers (2) maps
    // to InternalError exactly like the JVM maps LOCK_BELONG_TO_OTHERS (dapr4s's UnlockStatus has
    // no owner-mismatch case); InternalError (3), unknown values, and an absent status (JVM: null
    // response) all collapse to InternalError.
    response.status.toOption.fold(UnlockStatus.InternalError) {
      case 0 => UnlockStatus.Success
      case 1 => UnlockStatus.LockNotFound
      case _ => UnlockStatus.InternalError
    }
