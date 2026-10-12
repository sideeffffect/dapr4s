package dapr4s.lock

import dapr4s.*

import language.experimental.safe

/** Result status of an unlock operation. */
enum UnlockStatus:
  /** The lock was held by the caller and is now released. */
  case Success

  /** No lock exists for the resource. */
  case LockNotFound

  /** The lock exists but a different owner holds it; it is still held. */
  case LockBelongsToOthers

  /** The lock store failed, or the sidecar returned an unknown status. */
  case InternalError
