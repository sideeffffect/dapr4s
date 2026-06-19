package dapr4s.workflow

import dapr4s.*

import language.experimental.safe

/** Simple class name of a [[Workflow]] subclass, used to start instances.
  *
  * Must not be empty. Obtain via `classOf[MyWorkflow].getSimpleName` and pass to [[WorkflowCapability.start]] or
  * [[WorkflowCapability.startWithId]]. The value must match the simple class name under which a [[Workflow]] subclass
  * is registered in the same [[DaprApp]] — the server registers workflows by `getSimpleName` (e.g. `"OrderWorkflow"`,
  * not `"workflows.OrderWorkflow"`), so the same is also what appears in the HTTP API URL.
  */
opaque type WorkflowName = String
object WorkflowName:
  def apply(s: String): WorkflowName =
    require(s.nonEmpty, "WorkflowName must not be empty")
    s
  extension (n: WorkflowName) def value: String = n
