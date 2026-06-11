package dapr4s

import language.experimental.safe

/** Name of a Dapr state store component.
  *
  * Must not be empty. Must match the `name` field in the state store component's metadata YAML. Used when constructing
  * a [[StateCapability]] via [[DaprCapability.state]].
  *
  * Distinct from [[LockStoreName]]: a state store and a lock store are different Dapr building blocks with different
  * component types, so their names are not interchangeable.
  */
opaque type StateStoreName = String
object StateStoreName:
  def apply(s: String): StateStoreName =
    require(s.nonEmpty, "StateStoreName must not be empty")
    s
  extension (n: StateStoreName) def value: String = n
