package dapr.safe

/** Base class for Dapr workflow orchestration implementations.
  *
  * Extend this class to define a workflow. The `run` method is called by the Dapr workflow runtime whenever a new
  * instance of this workflow is started.
  *
  * Workflows must be **deterministic** — they cannot perform I/O, use random numbers, or depend on the current time
  * directly. All side effects must go through [[io.dapr.workflows.WorkflowContext]] APIs (callActivity, timers,
  * external events).
  *
  * {{{
  *   class OrderWorkflow extends DaprWorkflow:
  *     def run(ctx: io.dapr.workflows.WorkflowContext): Unit =
  *       val result = ctx.callActivity(classOf[ProcessPaymentActivity], orderId, classOf[String]).get()
  *       ctx.complete(result)
  * }}}
  *
  * Include in the `workflows` list of a [[dapr.safe.DaprApp]] returned from [[dapr.safe.DaprRuntime.serve]].
  */
@scala.caps.assumeSafe
abstract class DaprWorkflow extends io.dapr.workflows.Workflow:

  /** Implement workflow logic here. Called once per workflow instance start.
    *
    * Use `ctx.callActivity(...)`, `ctx.waitForExternalEvent(...)`, and `ctx.createTimer(...)` to schedule durable
    * tasks. Call `ctx.complete(output)` to mark the workflow as completed.
    */
  def run(ctx: io.dapr.workflows.WorkflowContext): Unit

  final override def create(): io.dapr.workflows.WorkflowStub | Null =
    val self = this
    // asInstanceOf erases the CC capture annotation on the stub — safe because
    // DaprWorkflow is @assumeSafe and the stub's lifecycle is managed by the runtime.
    val stub = new io.dapr.workflows.WorkflowStub:
      override def run(ctx: io.dapr.workflows.WorkflowContext | Null): Unit =
        if ctx != null then self.run(ctx)
    stub.asInstanceOf[io.dapr.workflows.WorkflowStub]

/** Base class for Dapr workflow activity implementations.
  *
  * Activities are the basic unit of work in a workflow — they perform I/O, call external services, or run CPU-intensive
  * tasks. Unlike orchestrations, activities are not required to be deterministic.
  *
  * {{{
  *   class ProcessPaymentActivity extends DaprActivity:
  *     def execute(ctx: io.dapr.workflows.WorkflowActivityContext): AnyRef =
  *       val orderId = ctx.getInput(classOf[String]).nn
  *       // do work...
  *       "payment-confirmed"
  * }}}
  *
  * Include in the `activities` list of a [[dapr.safe.DaprApp]] returned from [[dapr.safe.DaprRuntime.serve]].
  */
@scala.caps.assumeSafe
abstract class DaprActivity extends io.dapr.workflows.WorkflowActivity:

  /** Implement activity logic here. The return value is serialized and returned to the orchestration that scheduled
    * this activity.
    */
  def execute(ctx: io.dapr.workflows.WorkflowActivityContext): AnyRef

  final override def run(ctx: io.dapr.workflows.WorkflowActivityContext | Null): AnyRef | Null =
    if ctx == null then null else execute(ctx)
