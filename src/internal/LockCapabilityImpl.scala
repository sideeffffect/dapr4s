package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{LockRequest, UnlockRequest, UnlockResponseStatus}
import MonoOps.*
import NullOps.*

@scala.caps.assumeSafe
private[safe] final class LockCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: StoreName,
) extends DistributedLockCapability:

  private def previewClient: io.dapr.client.DaprPreviewClient =
    scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]

  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int): Boolean =
    val request = new LockRequest(storeName.value, resourceId.value, lockOwner.value, expirySeconds)
    previewClient.tryLock(request).awaitResult().toOption.exists(_.booleanValue())

  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    val request = new UnlockRequest(storeName.value, resourceId.value, lockOwner.value)
    previewClient.unlock(request).awaitResult().toOption.fold(UnlockStatus.InternalError) {
      case UnlockResponseStatus.SUCCESS               => UnlockStatus.Success
      case UnlockResponseStatus.LOCK_UNEXIST          => UnlockStatus.LockNotFound
      case UnlockResponseStatus.LOCK_BELONG_TO_OTHERS => UnlockStatus.InternalError
      case UnlockResponseStatus.INTERNAL_ERROR        => UnlockStatus.InternalError
    }
