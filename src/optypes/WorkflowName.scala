package dapr.safe

import language.experimental.safe

opaque type WorkflowName = String
object WorkflowName:
  def apply(s: String): WorkflowName =
    require(s.nonEmpty, "WorkflowName must not be empty")
    s
  extension (n: WorkflowName) def value: String = n
