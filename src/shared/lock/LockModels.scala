package dapr4s.lock

import dapr4s.*

import language.experimental.safe

/** Result status of an unlock operation. */
enum UnlockStatus:
  case Success
  case LockNotFound
  case InternalError
