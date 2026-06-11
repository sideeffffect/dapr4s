//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.scalajs.js

/** Server-side workflow/activity hosting on Scala.js — the JS counterpart of the JVM `DaprAppServer`'s
  * `WorkflowRuntimeBuilder` block, backed by the SDK's [[facade.WorkflowRuntime]] and the [[WorkflowCoroutine]] bridge.
  *
  * [[DaprAppServer.startAndBlock]] calls [[start]] exactly when `app.workflows` or `app.activities` is non-empty
  * (mirroring the JVM's "created only if needed" condition) and closes the returned [[WorkflowHost.Handle]] during
  * SIGINT/SIGTERM shutdown, after the HTTP server stops accepting connections (the JVM closes its `WorkflowRuntime` in
  * the shutdown hook the same way).
  */
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
    * Workflows register under their simple class names — the same `getSimpleName` rule as the JVM
    * (`DaprAppServer`/`WorkflowBridge`), because the name appears in user-visible API URLs and `WorkflowName(...)`
    * values. Activities register under their [[dapr4s.WorkflowActivity.activityName]]. One behavioural nuance versus
    * the JVM: the JS SDK's registry throws on a duplicate name at registration time (the JVM silently keeps the first
    * registration) — a loud failure for what is a bug either way.
    *
    * `runtime.start()` is awaited before returning to preserve the JVM ordering (`WorkflowRuntimeBuilder` → `rt.start`
    * → HTTP server bind); per the vendored worker it resolves as soon as the gRPC stub exists — the work-item stream
    * connects in the background with retries (see the [[facade.WorkflowRuntime]] doc), so this does not block on
    * sidecar availability, same as the JVM's non-blocking `start(false)`.
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
    val runtime = new facade.WorkflowRuntime(DaprCapabilityImpl.workflowClientOptions(sidecar))

    workflows.foreach { w =>
      // The TWorkflow function: create (NOT run) the coroutine generator for this execution. The executor awaits the
      // returned value, duck-types it as an async generator via Symbol.asyncIterator, and drives it — see the
      // WorkflowCoroutine doc for the full protocol. The closure captures only the @assumeSafe Workflow instance, so
      // no capture-erasure cast is needed for the SAM conversion to the facade's js.Function2.
      val fn: js.Function2[facade.SdkWorkflowContext, js.Any, js.Any] =
        (sdkCtx, input) => new WorkflowCoroutine(w, sdkCtx, input)
      runtime.registerWorkflowWithName(w.getClass.getSimpleName.nn, fn): Unit
    }

    // WHAT: asInstanceOf[AnyRef] erasing the DaprCapability's capture set before it is captured by the activity
    // callbacks below (and cast back at the use site in runActivity).
    // WHY: a DaprCapability carries a non-empty CC capture set; capturing it directly in a closure handed to a JS
    // facade method (whose js.Function2 type cannot carry capture annotations) is rejected by capture checking.
    // WHY SAFE: the capability lives for the whole server lifetime (the enclosing Dapr.serve scope — startAndBlock
    // never returns), so every activity invocation happens strictly within its lifetime. This is the exact same
    // erasure the JVM twin performs for the same reason (WorkflowActivityBridge's daprRef: AnyRef parameter).
    val daprRef: AnyRef = daprCapability.asInstanceOf[AnyRef]

    activities.foreach { a =>
      // The activity callback is invoked from a JS frame (the activity executor), so it must open its own js.async
      // entry before touching dapr4s code — activities may suspend freely on capability calls (orphan js.await)
      // inside it, the per-invocation analogue of the JVM's virtual-thread-per-activity. The returned js.Promise is
      // awaited by the SDK (activity-executor.js isPromise check); a rejection becomes the activity's
      // failureDetails → TASKFAILED → TaskFailedError inside the calling workflow, exactly like a JVM activity
      // exception, so no catch is wanted here.
      val fn: js.Function2[facade.SdkWorkflowActivityContext, js.Any, js.Any] =
        (_, input) => js.async(runActivity(a, daprRef, input))
      runtime.registerActivityWithName(a.activityName, fn): Unit
    }

    JsAwait.await(runtime.start())

    new Handle:
      private var closed = false
      def close(): Unit =
        // Fire-and-forget by contract: runtime.stop() is async (it drains in-flight work items for up to 30s — see
        // the facade doc) and close() runs in a signal-listener JS frame where suspension is impossible. The JVM
        // hook can block on WorkflowRuntime.close(); here the drain continues in the background while
        // DaprAppServer's bounded shutdown timer decides when the process exits. The rejection handler keeps a
        // failing stop (or a double close racing the drain) from becoming an unhandled rejection, which would kill
        // the process mid-shutdown.
        if !closed then
          closed = true
          val onError: js.Function1[Any, Unit] = err =>
            js.Dynamic.global.console.warn(s"dapr4s: workflow runtime stop failed during shutdown: $err"): Unit
          runtime.stop().`catch`[Unit](onError): Unit

  /** Decode the wire input, run the user activity with the (erasure-restored) capability, and return the encoded output
    * — the JS twin of the JVM `WorkflowActivityBridge.run`, including the identical wire convention: the input arrives
    * `JSON.parse`d (a JSON string under the dapr4s double-encoding convention, `undefined` when absent → `"null"`, both
    * via [[WorkflowContextImpl.jsonOf]]) and the returned codec-encoded string is `JSON.stringify`ed once by the
    * activity executor, reproducing the JVM's Jackson-serialized-String wire format.
    */
  private def runActivity[I, O](activity: WorkflowActivity[I, O], daprRef: AnyRef, input: js.Any): js.Any =
    val decoded = activity.inputCodec.decode(WorkflowContextImpl.jsonOf(input)) match
      case Right(v)  => v
      case Left(err) =>
        throw RuntimeException(s"Failed to decode activity input for '${activity.getClass.getSimpleName}'", err)
    // WHAT: asInstanceOf restoring the DaprCapability erased to AnyRef in start() above.
    // WHY/WHY SAFE: see the daprRef comment in start() — same contract as the JVM WorkflowActivityBridge.
    val output = activity.execute(decoded)(using daprRef.asInstanceOf[DaprCapability])
    activity.outputCodec.encode(output)
