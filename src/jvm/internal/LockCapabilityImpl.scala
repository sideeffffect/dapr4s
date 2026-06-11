//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import io.dapr.client.domain.{LockRequest, UnlockRequest, UnlockResponseStatus}
import scala.concurrent.duration.FiniteDuration
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[internal] final class LockCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: LockStoreName,
) extends LockCapability:

  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expiry: FiniteDuration): Boolean =
    val request = new LockRequest(storeName.value, resourceId.value, lockOwner.value, expiry.toSeconds.toInt)
    scope.clientPreview.tryLock(request).awaitResult().toOption.exists(_.booleanValue())

  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    val request = new UnlockRequest(storeName.value, resourceId.value, lockOwner.value)
    scope.clientPreview.unlock(request).awaitResult().toOption.fold(UnlockStatus.InternalError) {
      case UnlockResponseStatus.SUCCESS               => UnlockStatus.Success
      case UnlockResponseStatus.LOCK_UNEXIST          => UnlockStatus.LockNotFound
      case UnlockResponseStatus.LOCK_BELONG_TO_OTHERS => UnlockStatus.InternalError
      case UnlockResponseStatus.INTERNAL_ERROR        => UnlockStatus.InternalError
    }
