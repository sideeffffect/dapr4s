package dapr.safe

import language.experimental.safe

opaque type ActorId = String
object ActorId:
  def apply(s: String): ActorId = s
  extension (id: ActorId) def value: String = id
