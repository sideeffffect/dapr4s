package dapr.safe

import language.experimental.safe

opaque type ReminderName = String
object ReminderName:
  def apply(s: String): ReminderName =
    require(s.nonEmpty, "ReminderName must not be empty")
    s
  extension (n: ReminderName) def value: String = n
