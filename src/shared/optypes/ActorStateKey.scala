package dapr4s

import language.experimental.safe

/** Key identifying a value in a virtual actor's state, accessed via [[ActorContext]] / [[ActorState]].
  *
  * May be empty (the validity constraints depend on the backing actor state store). Uniqueness is scoped to the
  * combination of actor type and actor id; Dapr namespaces actor state by the actor instance internally, so different
  * actor instances may use the same key without conflict.
  *
  * Distinct from [[StateStoreKey]]: this addresses the per-instance state of a virtual actor, not app-level state held
  * by a [[StateCapability]].
  */
opaque type ActorStateKey = String
object ActorStateKey:
  def apply(s: String): ActorStateKey = s
  extension (k: ActorStateKey) def value: String = k
