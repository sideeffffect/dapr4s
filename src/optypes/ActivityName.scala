package dapr4s

import language.experimental.safe

/** Name under which a [[WorkflowActivity]] is registered with the runtime and scheduled from a workflow.
  *
  * Activity dispatch is by this string: the server registers each activity under its [[WorkflowActivity.activityName]]
  * and a workflow schedules it via [[WorkflowContext.callActivityByName(name:* callActivityByName(name, input)]]. Both
  * sides must agree on the name. For class-based activities the name defaults to the canonical class name; the
  * `dapr4s.derivation` engines compute a stable name from the implementation class and method.
  *
  * Must not be empty.
  */
opaque type ActivityName = String
object ActivityName:
  def apply(s: String): ActivityName =
    require(s.nonEmpty, "ActivityName must not be empty")
    s
  extension (n: ActivityName) def value: String = n
