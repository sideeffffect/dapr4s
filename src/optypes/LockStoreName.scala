package dapr4s

import language.experimental.safe

/** Name of a Dapr lock store component.
  *
  * Must not be empty. Must match the `name` field in the lock store component's metadata YAML. Used when constructing a
  * [[DistributedLockCapability]] via [[DaprCapability.lock]].
  *
  * Distinct from [[StateStoreName]]: a lock store and a state store are different Dapr building blocks with different
  * component types, so their names are not interchangeable.
  */
opaque type LockStoreName = String
object LockStoreName:
  def apply(s: String): LockStoreName =
    require(s.nonEmpty, "LockStoreName must not be empty")
    s
  extension (n: LockStoreName) def value: String = n
