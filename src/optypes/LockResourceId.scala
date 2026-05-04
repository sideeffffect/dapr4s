package dapr.safe

import language.experimental.safe

opaque type LockResourceId = String
object LockResourceId:
  def apply(s: String): LockResourceId =
    require(s.nonEmpty, "LockResourceId must not be empty")
    s
  extension (id: LockResourceId) def value: String = id
