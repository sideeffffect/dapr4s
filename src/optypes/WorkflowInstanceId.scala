package dapr.safe

import language.experimental.safe

opaque type WorkflowInstanceId = String
object WorkflowInstanceId:
  def apply(s: String): WorkflowInstanceId = s
  extension (id: WorkflowInstanceId) def value: String = id
