package dapr4s

import language.experimental.safe

/** The registered type name of a virtual actor class.
  *
  * Must not be empty. Must match the name used in the [[DaprApp]] actor registration and in any `ActorDefinition`
  * annotation or equivalent configuration so that the Dapr runtime can route invokeRoutes to the correct actor
  * implementation.
  */
opaque type ActorType = String
object ActorType:
  def apply(s: String): ActorType =
    require(s.nonEmpty, "ActorType must not be empty")
    s
  extension (t: ActorType) def value: String = t
