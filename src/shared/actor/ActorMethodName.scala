package dapr4s.actor

import dapr4s.*

import language.experimental.safe

/** Name of an actor method.
  *
  * Must not be empty. Used as the method name when invoking an actor via [[ActorCapability.invoke]], and as the method
  * key when registering an [[ActorMethodRoute]] on an actor definition.
  *
  * Distinct from [[InvokeMethodName]]: this dispatches to a method on an addressed, stateful actor instance, not an
  * HTTP route on a remote app. Actor timer and reminder callbacks use [[TimerName]] and [[ReminderName]] respectively.
  */
opaque type ActorMethodName = String
object ActorMethodName:
  def apply(s: String): ActorMethodName =
    require(s.nonEmpty, "ActorMethodName must not be empty")
    s
  extension (n: ActorMethodName) def value: String = n
