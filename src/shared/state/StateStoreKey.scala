package dapr4s.state

import dapr4s.*

import language.experimental.safe

/** Key identifying a value in a Dapr state store, accessed via [[StateCapability]].
  *
  * May be empty (the validity constraints depend on the backing state store). Uniqueness is scoped to the combination
  * of app-id and state store component; different applications using the same store may use the same key without
  * conflict because Dapr namespaces keys by app-id internally.
  *
  * Distinct from [[ActorStateKey]]: this addresses app-level state held by a [[StateCapability]], not the per-instance
  * state of a virtual actor.
  */
opaque type StateStoreKey = String
object StateStoreKey:
  def apply(s: String): StateStoreKey = s
  extension (k: StateStoreKey) def value: String = k
