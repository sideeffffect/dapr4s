package dapr4s.actor

import dapr4s.*

import language.experimental.safe

/** Name of a persistent actor reminder, unique within a single actor instance.
  *
  * Must not be empty. Unlike timers, reminders survive actor deactivation and are restored when the actor is
  * reactivated. The name must be unique among all reminders registered for a given actor instance; registering a
  * reminder with an existing name overwrites the previous one.
  */
opaque type ReminderName = String
object ReminderName:
  def apply(s: String): ReminderName =
    require(s.nonEmpty, "ReminderName must not be empty")
    s
  extension (n: ReminderName) def value: String = n
