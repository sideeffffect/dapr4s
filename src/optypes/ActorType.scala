package dapr.safe

import language.experimental.safe

opaque type ActorType = String
object ActorType:
  def apply(s: String): ActorType =
    require(s.nonEmpty, "ActorType must not be empty")
    s
  extension (t: ActorType) def value: String = t
