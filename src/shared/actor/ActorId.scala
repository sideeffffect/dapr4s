package dapr4s.actor

import dapr4s.*

import language.experimental.safe

/** Unique identifier for a virtual actor instance, scoped within an [[ActorType]].
  *
  * Two actors of different types may share the same `ActorId` without conflict; uniqueness is only required within a
  * single actor type. May be empty (Dapr does not enforce non-emptiness for actor IDs).
  */
opaque type ActorId = String
object ActorId:
  def apply(s: String): ActorId = s
  extension (id: ActorId) def value: String = id
