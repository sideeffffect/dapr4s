package dapr4s

import language.experimental.safe

/** Name of a Dapr state store or lock store component.
  *
  * Must not be empty. Must match the `name` field in the component's metadata YAML. Used when constructing a
  * [[StateCapability]] via [[DaprCapability.state]] or a [[DistributedLockCapability]] via [[DaprCapability.lock]].
  */
opaque type StoreName = String
object StoreName:
  def apply(s: String): StoreName =
    require(s.nonEmpty, "StoreName must not be empty")
    s
  extension (n: StoreName) def value: String = n
