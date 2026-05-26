package dapr4s

import language.experimental.safe

/** Fully-qualified class name of a [[Workflow]] subclass, used to start instances.
  *
  * Must not be empty. Obtain via `classOf[MyWorkflow].getCanonicalName` and pass to [[WorkflowCapability.start]] or
  * [[WorkflowCapability.startWithId]]. The value must match the canonical class name of a [[Workflow]] subclass
  * registered in the same [[DaprApp]].
  */
opaque type WorkflowName = String
object WorkflowName:
  def apply(s: String): WorkflowName =
    require(s.nonEmpty, "WorkflowName must not be empty")
    s
  extension (n: WorkflowName) def value: String = n
