package dapr.safe

import language.experimental.safe

/** Name of an actor method, service invocation route, or actor timer/reminder callback.
  *
  * Must not be empty. Used as the URL path segment in service invocation ([[ServiceInvocationCapability.invoke]]), as
  * the actor method name in [[ActorCapability.invoke]], and as the callback method name when registering actor timers
  * and reminders.
  */
opaque type MethodName = String
object MethodName:
  def apply(s: String): MethodName =
    require(s.nonEmpty, "MethodName must not be empty")
    s
  extension (n: MethodName) def value: String = n
