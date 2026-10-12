package dapr4s.workflow

import dapr4s.*

import language.experimental.safe

/** Name of an external event that a workflow instance is waiting for.
  *
  * Must not be empty. Passed to [[WorkflowContext.waitForExternalEvent]] inside workflow logic and to
  * [[WorkflowCapability.raiseEvent]] from the client side. Both sides must use the same name for the event to be
  * delivered.
  */
opaque type EventName = String
object EventName:
  def apply(s: String): EventName =
    require(s.nonEmpty, "EventName must not be empty")
    s
  extension (n: EventName) def value: String = n
