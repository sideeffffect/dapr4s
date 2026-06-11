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

/** Facade for `WorkflowRuntime` (root export of `@dapr/dapr`, `workflow/runtime/WorkflowRuntime.ts`) — the server-side
  * workflow/activity host. It shares [[WorkflowClientOptions]] with [[DaprWorkflowClient]] (same `generateEndpoint` +
  * API-token interceptor wiring) and drives the vendored durabletask `TaskHubGrpcWorker`.
  *
  * Lifecycle facts verified in the vendored sources (`workflow/internal/durabletask/worker/task-hub-grpc-worker.js`):
  *   - `start()` is async but resolves as soon as the gRPC stub is created — the work-item stream runs detached in the
  *     background (`internalRunWorker` is deliberately not awaited) and connection errors after the first attempt are
  *     retried with backoff, so awaiting `start()` does not wait for (or guarantee) sidecar connectivity.
  *   - `stop()` is async and slow by design: it cancels the work-item stream, polls in-flight work items for up to 30s,
  *     closes the stub, then sleeps 1s (grpc-node shutdown quirk). It rejects if the worker is not running.
  *   - Registering with a duplicate name throws synchronously (`registry.js`: "A '<name>' orchestrator already
  *     exists.").
  *
  * The `workflow` callback receives the '''public''' [[SdkWorkflowContext]] wrapper (WorkflowRuntime.js wraps the inner
  * `RuntimeOrchestrationContext` before invoking the registered function) and the `JSON.parse`d workflow input
  * (`undefined` when the instance was started without input). Its return value is `await`ed by the orchestration
  * executor and then duck-typed: anything with a callable `[Symbol.asyncIterator]` property is driven as an async
  * generator (`worker/orchestration-executor.js`, EXECUTIONSTARTED case) — which is exactly what
  * [[dapr4s.internal.WorkflowCoroutine]] hands back.
  *
  * The activity callback receives [[SdkWorkflowActivityContext]] and the `JSON.parse`d activity input; a returned
  * `js.Promise` is awaited by the activity executor (`worker/activity-executor.js` `isPromise` check) and the settled
  * value is `JSON.stringify`ed once onto the wire.
  */
@js.native
@JSImport("@dapr/dapr", "WorkflowRuntime")
private[internal] class WorkflowRuntime(options: WorkflowClientOptions) extends js.Object:
  def registerWorkflowWithName(
      name: String,
      workflow: js.Function2[SdkWorkflowContext, js.Any, js.Any],
  ): WorkflowRuntime = js.native
  def registerActivityWithName(
      name: String,
      fn: js.Function2[SdkWorkflowActivityContext, js.Any, js.Any],
  ): WorkflowRuntime = js.native
  def start(): js.Promise[Unit] = js.native
  def stop(): js.Promise[Unit] = js.native

/** Facade for the public `WorkflowContext` wrapper class (`workflow/runtime/WorkflowContext.ts`) handed to registered
  * workflow functions. Structural (no `@JSImport`): instances are only ever received from [[WorkflowRuntime]].
  *
  * Named `Sdk*` (unlike the other facades, which reuse the SDK names) because the natural names collide with the dapr4s
  * public types `WorkflowContext`/`Task` that the very same implementation files must also reference.
  *
  * Only the members dapr4s calls are declared. Verified against `WorkflowContext.js` + the inner
  * `worker/runtime-orchestration-context.js`:
  *   - `createTimer` accepts a JS `Date` or a '''number of seconds''' (`fireAt * 1000` is added to the deterministic
  *     `currentUtcDateTime` when a non-`Date` is passed) — declared here with the seconds overload only.
  *   - `callActivity` accepts the activity name or function; dapr4s always passes the registered name (string).
  *   - `whenAny` returns a `WhenAnyTask` whose result is the first-completed '''child `Task` object''' (not its value)
  *     — see `task/when-any-task.js` `onChildCompleted`.
  *   - `continueAsNew(newInput, saveEvents)` only records state (`setContinuedAsNew`); unlike the Java SDK it does not
  *     throw — the dapr4s impl adds the stack-unwinding signal itself (see `WorkflowContextImpl.continueAsNew`).
  */
@js.native
private[internal] trait SdkWorkflowContext extends js.Object:
  def getWorkflowInstanceId(): String = js.native
  def getCurrentUtcDateTime(): js.Date = js.native
  def isReplaying(): Boolean = js.native
  def createTimer(fireAtSeconds: Double): SdkTask = js.native
  def callActivity(activity: String, input: js.Any): SdkTask = js.native
  def waitForExternalEvent(name: String): SdkTask = js.native
  def continueAsNew(newInput: js.Any, saveEvents: Boolean): Unit = js.native
  def whenAny(tasks: js.Array[SdkTask]): SdkTask = js.native

/** Facade for the vendored durabletask `Task` base class (`workflow/internal/durabletask/task/task.js`) — the values
  * the orchestration executor accepts as generator yields (`runtime-orchestration-context.js` checks `value instanceof
  * Task`, so dapr4s must yield these very instances, never wrappers). Structural: instances are only ever produced by
  * [[SdkWorkflowContext]] methods.
  *
  * `isComplete`/`isFailed` are JS getter properties (declared parameterless). `getResult()` returns the completed value
  * and '''throws''' the stored `TaskFailedError` when the task failed. There is no cancellation concept in the JS SDK's
  * task model (the Java SDK's `isCancelled` has no counterpart).
  */
@js.native
private[internal] trait SdkTask extends js.Object:
  def isComplete: Boolean = js.native
  def isFailed: Boolean = js.native
  def getResult(): js.Any = js.native

/** Facade for the public `WorkflowActivityContext` wrapper (`workflow/runtime/WorkflowActivityContext.ts`) handed to
  * registered activity functions. Structural; dapr4s does not currently read it (activity input arrives as the second
  * callback argument), but the members are declared for completeness of the seam.
  */
@js.native
private[internal] trait SdkWorkflowActivityContext extends js.Object:
  def getWorkflowInstanceId(): String = js.native
  def getWorkflowActivityId(): Double = js.native

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
