//> using target.platform "scala-js"
package dapr4s.internal.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

// ---------------------------------------------------------------------------
// Facades for the workflow management client of `@dapr/dapr`.
//
// `DaprWorkflowClient` (workflow/client/DaprWorkflowClient.ts) talks gRPC
// directly to the sidecar via the vendored durabletask `TaskHubGrpcClient`
// (workflow/internal/durabletask/client/client.js) — it is the proper workflow
// client; the `client.workflow` building block on `DaprClient` is HTTP-only,
// deprecated-shaped, and not facaded here.
// ---------------------------------------------------------------------------

/** Facade for `DaprWorkflowClient` (root export of `@dapr/dapr`).
  *
  * Inputs/outputs/payloads are `JSON.stringify`-ed by the vendored client (`client.js`: `scheduleNewOrchestration`,
  * `raiseOrchestrationEvent`, `terminateOrchestration`), so callers control the wire format by choosing what JS value
  * to pass — see [[dapr4s.internal.WorkflowCapabilityImpl]] for the JVM-parity rules.
  */
@js.native
@JSImport("@dapr/dapr", "DaprWorkflowClient")
private[dapr4s] class DaprWorkflowClient(options: WorkflowClientOptions) extends js.Object:
  def scheduleNewWorkflow(
      workflow: String,
      input: js.UndefOr[js.Any],
      instanceId: js.UndefOr[String],
  ): js.Promise[String] = js.native
  def terminateWorkflow(workflowInstanceId: String, output: js.Any | Null): js.Promise[Unit] = js.native
  def getWorkflowState(
      workflowInstanceId: String,
      getInputsAndOutputs: Boolean,
  ): js.Promise[js.UndefOr[WorkflowState]] = js.native
  def waitForWorkflowCompletion(
      workflowInstanceId: String,
      fetchPayloads: Boolean,
      timeoutInSeconds: Double,
  ): js.Promise[js.UndefOr[WorkflowState]] = js.native
  def raiseEvent(workflowInstanceId: String, eventName: String, eventPayload: js.Any): js.Promise[Unit] = js.native
  def purgeWorkflow(workflowInstanceId: String): js.Promise[Boolean] = js.native
  def suspendWorkflow(workflowInstanceId: String): js.Promise[Unit] = js.native
  def resumeWorkflow(workflowInstanceId: String): js.Promise[Unit] = js.native
  def stop(): js.Promise[Unit] = js.native

/** Facade for `WorkflowClientOptions` (`types/workflow/WorkflowClientOption.ts`). All fields optional; the endpoint is
  * resolved as `${daprHost}:${daprPort}` through `GrpcEndpoint` exactly like `GRPCClient` does
  * (`workflow/internal/index.js` `generateEndpoint`).
  */
private[dapr4s] final class WorkflowClientOptions(
    val daprHost: js.UndefOr[String] = js.undefined,
    val daprPort: js.UndefOr[String] = js.undefined,
    val daprApiToken: js.UndefOr[String] = js.undefined,
) extends js.Object

/** Facade for `WorkflowState` (`workflow/client/WorkflowState.ts`) — a class with getters; modelled structurally
  * because we only ever consume instances returned by [[DaprWorkflowClient]].
  *
  * `runtimeStatus` is the numeric [[WorkflowRuntimeStatus]] enum. `createdAt`/`lastUpdatedAt` are JS `Date`s built from
  * the protobuf timestamps (`workflow/internal/durabletask/orchestration/index.js`). `serializedInput`/`Output` are
  * JSON strings, `undefined` when payload fetching was off or the value is absent.
  */
@js.native
private[internal] trait WorkflowState extends js.Object:
  def name: String = js.native
  def instanceId: String = js.native
  def runtimeStatus: Int = js.native
  def createdAt: js.Date = js.native
  def lastUpdatedAt: js.Date = js.native
  def serializedInput: js.UndefOr[String] = js.native
  def serializedOutput: js.UndefOr[String] = js.native

/** Facade for the numeric `WorkflowRuntimeStatus` enum (`workflow/runtime/WorkflowRuntimeStatus.ts`): RUNNING = 0,
  * COMPLETED = 1, CONTINUED_AS_NEW = 2, FAILED = 3, TERMINATED = 5, PENDING = 6, SUSPENDED = 7. Note there is no
  * CANCELED member (protobuf value 4) — the JS SDK omits it. Values are read off the real enum object rather than
  * hardcoded, so a renumbering upstream cannot silently corrupt the mapping.
  */
@js.native
@JSImport("@dapr/dapr", "WorkflowRuntimeStatus")
private[internal] object WorkflowRuntimeStatus extends js.Object:
  val RUNNING: Int = js.native
  val COMPLETED: Int = js.native
  val CONTINUED_AS_NEW: Int = js.native
  val FAILED: Int = js.native
  val TERMINATED: Int = js.native
  val PENDING: Int = js.native
  val SUSPENDED: Int = js.native
