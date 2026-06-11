//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*

/** Seam for server-side workflow/activity hosting on Scala.js — the JS counterpart of the JVM `DaprAppServer`'s
  * `WorkflowRuntimeBuilder` block.
  *
  * [[DaprAppServer.startAndBlock]] calls [[start]] exactly when `app.workflows` or `app.activities` is non-empty
  * (mirroring the JVM's "created only if needed" condition) and closes the returned [[WorkflowHost.Handle]] during
  * SIGINT/SIGTERM shutdown, after the HTTP server stops accepting connections (the JVM closes its `WorkflowRuntime` in
  * the shutdown hook the same way).
  */
// TODO(scala-js workflow-hosting phase): replace the throwing body with a real implementation over the
// facade'd @dapr/dapr WorkflowRuntime — registerWorkflowWithName/registerActivityWithName, the
// js.async-based coroutine bridge for Workflow.run, and a Handle that stops the runtime. The signature
// below is the stable seam: DaprAppServer already wires workflows, activities, the live DaprCapability
// (activities receive it, like the JVM's WorkflowActivityBridge), and the SidecarConfig (gRPC endpoint +
// api token for the runtime's durabletask connection, cf. DaprCapabilityImpl.workflowClientOptions).
@scala.caps.assumeSafe
private[internal] object WorkflowHost:

  /** A running workflow runtime. [[close]] must be synchronous and non-suspending: it is invoked from a Node signal
    * listener (a plain JavaScript frame), where JSPI suspension is impossible — see the shutdown path in
    * [[DaprAppServer]].
    */
  trait Handle:
    /** Stop the workflow runtime, draining in-flight orchestrations as the underlying SDK allows. */
    def close(): Unit

  /** Start hosting the given workflows and activities against the sidecar described by `sidecar`.
    *
    * @param workflows
    *   the [[dapr4s.Workflow]]s to register (under their simple class names, like the JVM)
    * @param activities
    *   the [[dapr4s.WorkflowActivity]]s to register (under their `activityName`s)
    * @param daprCapability
    *   the live capability scope activities run against (the JS analogue of the JVM `WorkflowActivityBridge`'s
    *   capability parameter)
    * @param sidecar
    *   connection settings for the runtime's gRPC channel (endpoint, api token)
    * @return
    *   a handle the server closes on shutdown
    */
  def start(
      workflows: List[Workflow],
      activities: List[WorkflowActivity[?, ?]],
      daprCapability: DaprCapability,
      sidecar: SidecarConfig,
  ): Handle =
    throw new UnsupportedOperationException("dapr4s workflow hosting on Scala.js lands in the next phase")
