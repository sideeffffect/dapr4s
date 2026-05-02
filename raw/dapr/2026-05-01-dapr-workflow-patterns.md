# Dapr Workflow Patterns

> Source: https://docs.dapr.io/developing-applications/building-blocks/workflow/workflow-patterns/
> Collected: 2026-05-01
> Published: Unknown

## Task Chaining

This pattern executes multiple workflow steps sequentially, where each step's output becomes the next step's input. It's ideal for data transformation pipelines requiring filtering, modification, and aggregation.

**Key Benefits:**
- The workflow is expressed as a simple series of statements in the programming language of your choice
- Automatic retry policies with exponential backoff
- Built-in error handling and compensation logic
- Progress is saved automatically with automatic resumption from the last completed step

**Python Example:**
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

**Go Example:**
```go
func TaskChainWorkflow(ctx *workflow.WorkflowContext) (any, error) {
	var input int
	if err := ctx.GetInput(&input); err != nil {
		return "", err
	}
	var result1 int
	if err := ctx.CallActivity(Step1, workflow.ActivityInput(input)).Await(&result1); err != nil {
		return nil, err
	}
	var result2 int
	if err := ctx.CallActivity(Step2, workflow.ActivityInput(input)).Await(&result2); err != nil {
		return nil, err
	}
	var result3 int
	if err := ctx.CallActivity(Step3, workflow.ActivityInput(input)).Await(&result3); err != nil {
		return nil, err
	}
	return []int{result1, result2, result3}, nil
}
```

**.NET Example:**
```csharp
var retryOptions = new WorkflowTaskOptions
{
    RetryPolicy = new WorkflowRetryPolicy(
        firstRetryInterval: TimeSpan.FromMinutes(1),
        backoffCoefficient: 2.0,
        maxRetryInterval: TimeSpan.FromHours(1),
        maxNumberOfAttempts: 10),
};

try
{
    var result1 = await context.CallActivityAsync<string>("Step1", wfInput, retryOptions);
    var result2 = await context.CallActivityAsync<byte[]>("Step2", result1, retryOptions);
    var result3 = await context.CallActivityAsync<long[]>("Step3", result2, retryOptions);
    return string.Join(", ", result4);
}
catch (TaskFailedException)
{
    await context.CallActivityAsync<long[]>("MyCompensation", options: retryOptions);
    throw;
}
```

**Java Example:**
```java
public class ChainWorkflow extends Workflow {
    @Override
    public WorkflowStub create() {
        return ctx -> {
            StringBuilder sb = new StringBuilder();
            String wfInput = ctx.getInput(String.class);
            String result1 = ctx.callActivity("Step1", wfInput, String.class).await();
            String result2 = ctx.callActivity("Step2", result1, String.class).await();
            String result3 = ctx.callActivity("Step3", result2, String.class).await();
            String result = sb.append(result1).append(',').append(result2).append(',').append(result3).toString();
            ctx.complete(result);
        };
    }
}
```

---

## Fan-Out/Fan-In

This pattern executes numerous tasks simultaneously across multiple workers, waits for completion, then aggregates results. It addresses scenarios where processing speed and parallelism are critical.

**Key Advantages:**
- Dynamic or static task counts supported
- Automatic result aggregation
- If a workflow starts 100 parallel task executions and only 40 complete before the process crashes, the workflow restarts itself automatically and only schedules the remaining 60 tasks
- Optional concurrency limiting

**Python Example:**
```python
def batch_processing_workflow(ctx: wf.DaprWorkflowContext, wf_input: int):
    work_batch = yield ctx.call_activity(get_work_batch, input=wf_input)
    parallel_tasks = [ctx.call_activity(process_work_item, input=work_item) for work_item in work_batch]
    outputs = yield wf.when_all(parallel_tasks)
    total = sum(outputs)
    yield ctx.call_activity(process_results, input=total)
```

**Go Example:**
```go
func BatchProcessingWorkflow(ctx *workflow.WorkflowContext) (any, error) {
	var input int
	if err := ctx.GetInput(&input); err != nil {
		return 0, err
	}
	var workBatch []int
	if err := ctx.CallActivity(GetWorkBatch, workflow.ActivityInput(input)).Await(&workBatch); err != nil {
		return 0, err
	}
	parallelTasks := workflow.NewTaskSlice(len(workBatch))
	for i, workItem := range workBatch {
		parallelTasks[i] = ctx.CallActivity(ProcessWorkItem, workflow.ActivityInput(workItem))
	}
	var outputs int
	for _, task := range parallelTasks {
		var output int
		err := task.Await(&output)
		if err == nil {
			outputs += output
		} else {
			return 0, err
		}
	}
	if err := ctx.CallActivity(ProcessResults, workflow.ActivityInput(outputs)).Await(nil); err != nil {
		return 0, err
	}
	return 0, nil
}
```

**.NET Example (with concurrency limit of 5):**
```csharp
object[] workBatch = await context.CallActivityAsync<object[]>("GetWorkBatch", null);
const int MaxParallelism = 5;
var results = new List<int>();
var inFlightTasks = new HashSet<Task<int>>();
foreach(var workItem in workBatch)
{
  if (inFlightTasks.Count >= MaxParallelism)
  {
    var finishedTask = await Task.WhenAny(inFlightTasks);
    results.Add(finishedTask.Result);
    inFlightTasks.Remove(finishedTask);
  }
  inFlightTasks.Add(context.CallActivityAsync<int>("ProcessWorkItem", workItem));
}
results.AddRange(await Task.WhenAll(inFlightTasks));
var sum = results.Sum(t => t);
await context.CallActivityAsync("PostResults", sum);
```

**Java Example:**
```java
public class FaninoutWorkflow extends Workflow {
    @Override
    public WorkflowStub create() {
        return ctx -> {
            Object[] workBatch = ctx.callActivity("GetWorkBatch", Object[].class).await();
            List<Task<Integer>> tasks = Arrays.stream(workBatch)
                    .map(workItem -> ctx.callActivity("ProcessWorkItem", workItem, int.class))
                    .collect(Collectors.toList());
            List<Integer> results = ctx.allOf(tasks).await();
            int sum = results.stream().mapToInt(Integer::intValue).sum();
            ctx.complete(sum);
        };
    }
}
```

---

## Async HTTP APIs

Asynchronous HTTP APIs are typically implemented using the Asynchronous Request-Reply pattern. Implementing this pattern traditionally involves:

1. A client sends a request to an HTTP API endpoint (the *start API*)
2. The *start API* writes a message to a backend queue, which triggers the start of a long-running operation
3. Immediately after scheduling the backend operation, the *start API* returns an HTTP 202 response with an identifier for polling
4. The *status API* queries a database that contains the status of the long-running operation
5. The client repeatedly polls the *status API* until a timeout expires or a "completion" response is received

The Dapr workflow HTTP API supports the asynchronous request-reply pattern out-of-the-box, without requiring any code or state management.

```bash
# Start workflow
curl -X POST http://localhost:3500/v1.0/workflows/dapr/OrderProcessingWorkflow/start?instanceID=12345678 \
  -d '{"Name":"Paperclips","Quantity":1,"TotalCost":9.95}'
# Returns: {"instanceID":"12345678"}

# Poll status
curl http://localhost:3500/v1.0/workflows/dapr/12345678
```

In-progress response:
```json
{
  "instanceID": "12345678",
  "workflowName": "OrderProcessingWorkflow",
  "createdAt": "2023-05-03T23:22:11.143069826Z",
  "lastUpdatedAt": "2023-05-03T23:22:22.460025267Z",
  "runtimeStatus": "RUNNING",
  "properties": {
    "dapr.workflow.custom_status": "",
    "dapr.workflow.input": "{\"Name\":\"Paperclips\",\"Quantity\":1,\"TotalCost\":9.95}"
  }
}
```

Completed response:
```json
{
  "instanceID": "12345678",
  "workflowName": "OrderProcessingWorkflow",
  "createdAt": "2023-05-03T23:30:11.381146313Z",
  "lastUpdatedAt": "2023-05-03T23:30:52.923870615Z",
  "runtimeStatus": "COMPLETED",
  "properties": {
    "dapr.workflow.custom_status": "",
    "dapr.workflow.input": "{\"Name\":\"Paperclips\",\"Quantity\":1,\"TotalCost\":9.95}",
    "dapr.workflow.output": "{\"Processed\":true}"
  }
}
```

---

## Monitor Pattern

The monitor pattern is a recurring process that typically:

1. Checks the status of a system
2. Takes some action based on that status (e.g., send a notification)
3. Sleeps for some period of time
4. Repeats

Dapr Workflow supports this pattern natively via the *continue-as-new* API, which lets workflow authors restart a workflow function from the beginning with a new input (rather than writing an infinite while-loop, which is an anti-pattern).

**Python Example:**
```python
from dataclasses import dataclass
from datetime import timedelta
import random
import dapr.ext.workflow as wf

@dataclass
class JobStatus:
    job_id: str
    is_healthy: bool

def status_monitor_workflow(ctx: wf.DaprWorkflowContext, job: JobStatus):
    status = yield ctx.call_activity(check_status, input=job)
    if not ctx.is_replaying:
        print(f"Job '{job.job_id}' is {status}.")
    if status == "healthy":
        job.is_healthy = True
        next_sleep_interval = 60
    else:
        if job.is_healthy:
            job.is_healthy = False
            ctx.call_activity(send_alert, input=f"Job '{job.job_id}' is unhealthy!")
        next_sleep_interval = 5
    yield ctx.create_timer(fire_at=ctx.current_utc_datetime + timedelta(minutes=next_sleep_interval))
    ctx.continue_as_new(job)
```

**.NET Example:**
```csharp
public override async Task<object> RunAsync(WorkflowContext context, MyEntityState myEntityState)
{
    TimeSpan nextSleepInterval;
    var status = await context.CallActivityAsync<string>("GetStatus");
    if (status == "healthy")
    {
        myEntityState.IsHealthy = true;
        nextSleepInterval = TimeSpan.FromMinutes(60);
    }
    else
    {
        if (myEntityState.IsHealthy)
        {
            myEntityState.IsHealthy = false;
            await context.CallActivityAsync("SendAlert", myEntityState);
        }
        nextSleepInterval = TimeSpan.FromMinutes(5);
    }
    await context.CreateTimer(nextSleepInterval);
    context.ContinueAsNew(myEntityState);
    return null;
}
```

**Java Example:**
```java
public class MonitorWorkflow extends Workflow {
  @Override
  public WorkflowStub create() {
    return ctx -> {
      Duration nextSleepInterval;
      var status = ctx.callActivity(DemoWorkflowStatusActivity.class.getName(), DemoStatusActivityOutput.class).await();
      var isHealthy = status.getIsHealthy();
      if (isHealthy) {
        nextSleepInterval = Duration.ofMinutes(60);
      } else {
        ctx.callActivity(DemoWorkflowAlertActivity.class.getName()).await();
        nextSleepInterval = Duration.ofMinutes(5);
      }
      try {
        ctx.createTimer(nextSleepInterval);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      ctx.continueAsNew();
    };
  }
}
```

**Go Example:**
```go
func StatusMonitorWorkflow(ctx *workflow.WorkflowContext) (any, error) {
	var sleepInterval time.Duration
	var job JobStatus
	if err := ctx.GetInput(&job); err != nil {
		return "", err
	}
	var status string
	if err := ctx.CallActivity(CheckStatus, workflow.ActivityInput(job)).Await(&status); err != nil {
		return "", err
	}
	if status == "healthy" {
		job.IsHealthy = true
		sleepInterval = time.Minutes * 60
	} else {
		if job.IsHealthy {
			job.IsHealthy = false
			err := ctx.CallActivity(SendAlert, workflow.ActivityInput(fmt.Sprintf("Job '%s' is unhealthy!", job.JobID))).Await(nil)
			if err != nil {
				return "", err
			}
		}
		sleepInterval = time.Minutes * 5
	}
	if err := ctx.CreateTimer(sleepInterval).Await(nil); err != nil {
		return "", err
	}
	ctx.ContinueAsNew(job, false)
	return "", nil
}
```

A workflow implementing the monitor pattern can loop forever or terminate itself gracefully by not calling *continue-as-new*.

Note: This pattern can also be expressed using actors and reminders. The difference is that this workflow is expressed as a single function with inputs and state stored in local variables. Workflows can also execute a sequence of actions with stronger reliability guarantees.

---

## External System Interaction (Human Approval)

In some cases, a workflow may need to pause and wait for an external system to perform some action. A very common scenario is when a workflow needs to pause and wait for a human — for example when approving a purchase order.

Dapr Workflow supports this pattern via the external events feature:

1. A workflow is triggered when a purchase order is received.
2. A rule in the workflow determines that a human needs to perform some action (e.g., the purchase order cost exceeds an auto-approval threshold).
3. The workflow sends a notification requesting a human action (e.g., an email with an approval link).
4. The workflow pauses and waits for the human to either approve or reject the order by clicking on a link.
5. If the approval isn't received within the specified time, the workflow resumes and performs compensation logic, such as canceling the order.

**Python Example:**
```python
def purchase_order_workflow(ctx: wf.DaprWorkflowContext, order: Order):
    if order.cost < 1000:
        return "Auto-approved"
    yield ctx.call_activity(send_approval_request, input=order)
    approval_event = ctx.wait_for_external_event("approval_received")
    timeout_event = ctx.create_timer(timedelta(hours=24))
    winner = yield wf.when_any([approval_event, timeout_event])
    if winner == timeout_event:
        return "Cancelled"
    yield ctx.call_activity(place_order, input=order)
    approval_details = Approval.from_dict(approval_event.get_result())
    return f"Approved by '{approval_details.approver}'"
```

**.NET Example:**
```csharp
public override async Task<OrderResult> RunAsync(WorkflowContext context, OrderPayload order)
{
    if (order.TotalCost > OrderApprovalThreshold)
    {
        try
        {
            await context.CallActivityAsync(nameof(RequestApprovalActivity), order);
            ApprovalResult approvalResult = await context.WaitForExternalEventAsync<ApprovalResult>(
                eventName: "ManagerApproval",
                timeout: TimeSpan.FromDays(3));
            if (approvalResult == ApprovalResult.Rejected)
                return new OrderResult(Processed: false);
        }
        catch (TaskCanceledException)
        {
            return new OrderResult(Processed: false);
        }
    }
    return new OrderResult(Processed: true);
}
```

**Java Example:**
```java
public class ExternalSystemInteractionWorkflow extends Workflow {
    @Override
    public WorkflowStub create() {
        return ctx -> {
            Integer orderCost = ctx.getInput(int.class);
            if (orderCost > ORDER_APPROVAL_THRESHOLD) {
                try {
                    ctx.callActivity("RequestApprovalActivity", orderCost, Void.class).await();
                    boolean approved = ctx.waitForExternalEvent("ManagerApproval", Duration.ofDays(3), boolean.class).await();
                    if (!approved) {
                        ctx.complete("Process reject");
                    }
                } catch (TaskCanceledException e) {
                    ctx.complete("Process cancel");
                }
            }
            ctx.complete("Process approved");
        };
    }
}
```

**Go Example:**
```go
func PurchaseOrderWorkflow(ctx *workflow.WorkflowContext) (any, error) {
	var order Order
	if err := ctx.GetInput(&order); err != nil {
		return "", err
	}
	if order.Cost < 1000 {
		return "Auto-approved", nil
	}
	if err := ctx.CallActivity(SendApprovalRequest, workflow.ActivityInput(order)).Await(nil); err != nil {
		return "", err
	}
	var approval Approval
	if err := ctx.WaitForExternalEvent("approval_received", time.Hour*24).Await(&approval); err != nil {
		return "error/cancelled", err
	}
	if err := ctx.CallActivity(PlaceOrder, workflow.ActivityInput(order)).Await(nil); err != nil {
		return "", err
	}
	return fmt.Sprintf("Approved by %s", approval.Approver), nil
}
```

Raising the external event from outside the workflow:

```python
# Python
d.raise_workflow_event(
    instance_id=instance_id,
    workflow_component="dapr",
    event_name="approval_received",
    event_data=asdict(Approval("Jane Doe")))
```

```csharp
// .NET
await daprClient.RaiseWorkflowEventAsync(
    instanceId: orderId,
    workflowComponent: "dapr",
    eventName: "ManagerApproval",
    eventData: ApprovalResult.Approved);
```

External events don't have to be directly triggered by humans — they can also be triggered by other systems (e.g., a payment system publishing to a pub/sub topic).
