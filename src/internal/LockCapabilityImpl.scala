package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{LockRequest, UnlockRequest, UnlockResponseStatus}
import MonoOps.*

@scala.caps.assumeSafe
private[safe] final class LockCapabilityImpl(
    scope: DaprCapabilityImpl,
    val storeName: StoreName,
) extends DistributedLockCapability:

  def tryLock(resourceId: LockResourceId, lockOwner: LockOwner, expirySeconds: Int): Boolean =
    val previewClient = scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]
    val request = new LockRequest(storeName.value, resourceId.value, lockOwner.value, expirySeconds)
    val result: java.lang.Boolean | Null =
      previewClient.tryLock(request).awaitResult()
    if result == null then false
    else result.booleanValue()

  def unlock(resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    val previewClient = scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]
    val request = new UnlockRequest(storeName.value, resourceId.value, lockOwner.value)
    val status: UnlockResponseStatus | Null =
      previewClient.unlock(request).awaitResult()
    if status == null then UnlockStatus.InternalError
    else
      status match
        case UnlockResponseStatus.SUCCESS               => UnlockStatus.Success
        case UnlockResponseStatus.LOCK_UNEXIST          => UnlockStatus.LockNotFound
        case UnlockResponseStatus.LOCK_BELONG_TO_OTHERS => UnlockStatus.InternalError
        case UnlockResponseStatus.INTERNAL_ERROR        => UnlockStatus.InternalError
