# Dapr Workflow Patterns

> Sources: Dapr documentation, Unknown
> Raw: [dapr-workflow-patterns](../../raw/dapr/2026-05-01-dapr-workflow-patterns.md)
> Updated: 2026-05-01

## Overview

Dapr Workflow provides several composable patterns for building reliable, stateful applications. All patterns share the same foundational guarantees: deterministic replay from event-sourced history, automatic checkpointing after each activity, and transparent crash recovery. Code is written in the host language (Python, Go, .NET, Java, JavaScript) and executed by the Dapr workflow engine.

## Task Chaining

The simplest pattern: activities execute sequentially, with each step's output fed as input to the next. Suited for linear pipelines (parse → transform → persist) where steps cannot safely parallelize.

```python
def task_chain_workflow(ctx: wf.DaprWorkflowContext, wf_input: int):
    try:
        result1 = yield ctx.call_activity(step1, input=wf_input)
        result2 = yield ctx.call_activity(step2, input=result1)
        result3 = yield ctx.call_activity(step3, input=result2)
    except Exception as e:
        yield ctx.call_activity(error_handler, input=str(e))
        raise
    return [result1, result2, result3]
```

If the process crashes mid-workflow, execution resumes from the last completed checkpoint — earlier results are replayed from the event-sourced history.

Compensation on failure (for saga-like rollback) is handled in the `except` block by invoking a compensating activity before re-raising.

## Fan-Out / Fan-In

Activities are launched in parallel and results aggregated once all complete. The workflow engine automatically tracks which subtasks have been scheduled, so if a crash occurs after 40 of 100 subtasks complete, only the remaining 60 are re-scheduled on restart.

```python
def batch_processing_workflow(ctx: wf.DaprWorkflowContext, wf_input: int):
    work_batch = yield ctx.call_activity(get_work_batch, input=wf_input)
    parallel_tasks = [ctx.call_activity(process_work_item, input=item) for item in work_batch]
    outputs = yield wf.when_all(parallel_tasks)
    total = sum(outputs)
    yield ctx.call_activity(process_results, input=total)
```

**Concurrency limiting** (e.g., max 5 in-flight) is supported in .NET via `Task.WhenAny` drain loops, and similarly in other SDKs.

```java
// Java: ctx.allOf(tasks) waits for all parallel tasks
List<Task<Integer>> tasks = Arrays.stream(workBatch)
    .map(item -> ctx.callActivity("ProcessWorkItem", item, int.class))
    .collect(Collectors.toList());
List<Integer> results = ctx.allOf(tasks).await();
```

## Async HTTP APIs (Asynchronous Request-Reply)

Long-running workflows integrate naturally with the HTTP asynchronous request-reply pattern. The Dapr workflow HTTP API handles polling protocol, `202` responses, and status queries without any additional code:

```bash
# 1. Start the workflow — returns immediately with instance ID
curl -X POST http://localhost:3500/v1.0/workflows/dapr/OrderProcessingWorkflow/start?instanceID=12345678 \
  -d '{"Name":"Paperclips","Quantity":1,"TotalCost":9.95}'
# → {"instanceID":"12345678"}

# 2. Poll for completion
curl http://localhost:3500/v1.0/workflows/dapr/12345678
```

Status response while running:
```json
{
  "instanceID": "12345678",
  "runtimeStatus": "RUNNING",
  ...
}
```

Status response when done:
```json
{
  "instanceID": "12345678",
  "runtimeStatus": "COMPLETED",
  "properties": { "dapr.workflow.output": "{\"Processed\":true}" }
}
```

The client polls until `runtimeStatus` is `COMPLETED`, `FAILED`, or `TERMINATED`.

## Monitor Pattern (Eternal Workflows)

For recurring health checks or polling tasks, the monitor pattern uses `continueAsNew` to restart the workflow function from the beginning with updated state — avoiding both infinite loops (anti-pattern) and cron-based schedulers (inflexible).

```python
def status_monitor_workflow(ctx: wf.DaprWorkflowContext, job: JobStatus):
    status = yield ctx.call_activity(check_status, input=job)
    if status == "healthy":
        job.is_healthy = True
        next_sleep_interval = 60   # check less often when healthy
    else:
        if job.is_healthy:
            job.is_healthy = False
            ctx.call_activity(send_alert, input=f"Job '{job.job_id}' is unhealthy!")
        next_sleep_interval = 5    # check more often when unhealthy

    yield ctx.create_timer(fire_at=ctx.current_utc_datetime + timedelta(minutes=next_sleep_interval))
    ctx.continue_as_new(job)      # restart from top, new JobStatus becomes input
```

```java
// Java
ctx.createTimer(nextSleepInterval);
ctx.continueAsNew();   // restarts workflow with new state
```

```go
// Go
ctx.CreateTimer(sleepInterval).Await(nil)
ctx.ContinueAsNew(job, false)
```

The workflow terminates gracefully by simply not calling `continueAsNew` (e.g., when a job is deleted).

**Note:** This pattern overlaps with actors + reminders, but the workflow form expresses the full logic in a single function with stronger ordering and reliability guarantees.

## External System Interaction (Human Approval / Saga)

Workflows can pause indefinitely while waiting for an external event — a human approval, a payment confirmation, or any signal from another system. The workflow suspends without consuming resources until the event arrives.

```python
def purchase_order_workflow(ctx: wf.DaprWorkflowContext, order: Order):
    if order.cost < 1000:
        return "Auto-approved"                        # skip approval for small orders

    yield ctx.call_activity(send_approval_request, input=order)

    approval_event = ctx.wait_for_external_event("approval_received")
    timeout_event  = ctx.create_timer(timedelta(hours=24))
    winner = yield wf.when_any([approval_event, timeout_event])

    if winner == timeout_event:
        return "Cancelled"                            # saga compensation: cancel

    yield ctx.call_activity(place_order, input=order)
    return f"Approved by '{approval_event.get_result().approver}'"
```

Raising the event from outside (e.g., from an approval service or pub/sub listener):

```python
d.raise_workflow_event(instance_id=id, workflow_component="dapr",
                       event_name="approval_received",
                       event_data=asdict(Approval("Jane Doe")))
```

```csharp
await daprClient.RaiseWorkflowEventAsync(
    instanceId: orderId, workflowComponent: "dapr",
    eventName: "ManagerApproval", eventData: ApprovalResult.Approved);
```

```java
ctx.waitForExternalEvent("ManagerApproval", Duration.ofDays(3), boolean.class).await();
```

External events can come from humans (email link → webhook) or from other systems (pub/sub message → raise-event call). This pattern is the building block for both human-in-the-loop workflows and distributed sagas with compensation.

## Child Workflows

Dapr supports scheduling a workflow to call another workflow as a child process. Each child maintains its own instance ID, history, and status independently, while termination of the parent propagates to children. Child workflows are useful for:

- Breaking a large workflow into reusable, independently observable units
- Implementing recursive or recursive-parallel structures
- Spanning work across multiple applications

Child workflow scheduling and awaiting follows the same `callActivity` / `await` pattern but targets a workflow type instead of an activity.

## Compensation Transactions (Saga)

Saga-style compensation is a first-class concern in task-chaining and external-event patterns. When a step fails:
1. The workflow catches the exception (or timeout).
2. It calls compensating activities in reverse order to undo completed steps.
3. It re-raises or returns a failure result.

```csharp
try
{
    var result1 = await context.CallActivityAsync<string>("Step1", wfInput, retryOptions);
    var result2 = await context.CallActivityAsync<byte[]>("Step2", result1, retryOptions);
    var result3 = await context.CallActivityAsync<long[]>("Step3", result2, retryOptions);
}
catch (TaskFailedException)
{
    await context.CallActivityAsync<long[]>("MyCompensation", options: retryOptions);
    throw;
}
```

Because Dapr Workflow is durable, the compensation activity itself will be retried on failure, giving strong eventually-consistent semantics.

## See Also

- [Dapr Workflows](dapr-workflows.md)
- [Dapr Actors Deep Dive](dapr-actors-deep-dive.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Java SDK](dapr-java-sdk.md)
