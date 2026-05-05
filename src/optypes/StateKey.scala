package dapr.safe

import language.experimental.safe

/** Key identifying a value in a Dapr state store.
  *
  * May be empty (the validity constraints depend on the backing state store). Uniqueness is scoped to the combination
  * of app-id and state store component; different actor types or applications using the same store may use the same key
  * without conflict because Dapr namespaces keys by app-id internally.
  */
opaque type StateKey = String
object StateKey:
  def apply(s: String): StateKey = s
  extension (k: StateKey) def value: String = k
