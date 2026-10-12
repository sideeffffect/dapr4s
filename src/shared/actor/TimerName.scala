package dapr4s.actor

import dapr4s.*

import language.experimental.safe

/** Name of a non-persistent actor timer, unique within a single actor instance.
  *
  * Must not be empty. Timers are lost if the actor is deactivated; use [[ReminderName]]-based reminders instead when
  * durability across deactivations is required. The name must be unique among all timers registered for a given actor
  * instance; registering a timer with an existing name overwrites the previous one.
  */
opaque type TimerName = String
object TimerName:
  def apply(s: String): TimerName =
    require(s.nonEmpty, "TimerName must not be empty")
    s
  extension (n: TimerName) def value: String = n
