package dapr.safe

import language.experimental.safe

opaque type LockOwner = String
object LockOwner:
  def apply(s: String): LockOwner =
    require(s.nonEmpty, "LockOwner must not be empty")
    s
  extension (o: LockOwner) def value: String = o
