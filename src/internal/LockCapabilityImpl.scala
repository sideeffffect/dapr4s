package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.domain.{LockRequest, UnlockRequest, UnlockResponseStatus}
import language.experimental.saferExceptions

@scala.caps.assumeSafe
private[safe] final class LockCapabilityImpl(
    scope: DaprScopeImpl,
    val storeName: StoreName
) extends DistributedLockCapability:

  private def checkOpen(): Unit =
    if scope.isClosed then
      throw IllegalStateException("Capability is closed: DaprScope has been closed")

  def tryLock(resourceId: String, lockOwner: String, expirySeconds: Int): Boolean throws DaprLockException =
    checkOpen()
    try
      val previewClient = scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]
      val request = new LockRequest(storeName.value, resourceId, lockOwner, expirySeconds)
      val result: java.lang.Boolean | Null =
        previewClient.tryLock(request).block()
      if result == null then false
      else result.booleanValue()
    catch
      case e: DaprLockException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprLockException(e.getMessage.nn, e)
      case e: ClassCastException =>
        throw DaprLockException("Distributed lock requires DaprPreviewClient (not available)", e)

  def unlock(resourceId: String, lockOwner: String): UnlockStatus throws DaprLockException =
    checkOpen()
    try
      val previewClient = scope.client.asInstanceOf[io.dapr.client.DaprPreviewClient]
      val request = new UnlockRequest(storeName.value, resourceId, lockOwner)
      val status: UnlockResponseStatus | Null =
        previewClient.unlock(request).block()
      if status == null then UnlockStatus.InternalError
      else
        status match
          case UnlockResponseStatus.SUCCESS              => UnlockStatus.Success
          case UnlockResponseStatus.LOCK_UNEXIST         => UnlockStatus.LockNotFound
          case UnlockResponseStatus.LOCK_BELONG_TO_OTHERS => UnlockStatus.InternalError
          case UnlockResponseStatus.INTERNAL_ERROR       => UnlockStatus.InternalError
    catch
      case e: DaprLockException => throw e
      case e: io.dapr.exceptions.DaprException =>
        throw DaprLockException(e.getMessage.nn, e)
      case e: ClassCastException =>
        throw DaprLockException("Distributed lock requires DaprPreviewClient (not available)", e)
