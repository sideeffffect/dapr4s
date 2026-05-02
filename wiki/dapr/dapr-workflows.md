# Dapr Workflows

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-workflow-overview](../../raw/dapr/2026-05-01-dapr-workflow-overview.md)
> Updated: 2026-05-01

## Overview

Dapr Workflow provides a durable, fault-tolerant workflow orchestration runtime for long-running business processes spanning multiple microservices. It is built on top of Dapr Actors and uses event sourcing (append-only history) for durability. Workflow code must be strictly deterministic, with all side effects isolated to activities.

## Core Concepts

### Workflow

A **workflow** is an orchestration function that defines the sequence and logic of a business process. It schedules activities, child workflows, timers, and waits for external events. The workflow function itself must be **pure and deterministic** — it will be replayed from the beginning every time it resumes.

### Activity

An **activity** is a basic unit of work called by a workflow. Activities are where all non-deterministic work happens:
- Calling external services or databases
- CPU-intensive computation
- I/O operations
- Generating random numbers or UUIDs
- Reading environment variables or the current time

Activities execute **at-least-once** — implement them to be idempotent.

### Child Workflows

A workflow can start other workflows as children. Each child has:
- Its own instance ID, history, and status (independent of parent)
- Concurrent distribution across cluster nodes
- Automatic termination when the parent is terminated

Child workflows help decompose large processes and keep parent history small.

## Execution Model — Event Sourcing

Dapr Workflow uses **event sourcing with replay**:

1. Workflow runs until it awaits an async operation (activity, timer, external event)
2. State is checkpointed to the actor state store as an append-only history log
3. Workflow unloads from memory
4. When the awaited operation completes, workflow **replays from the beginning**
5. On replay, already-completed tasks return their cached results immediately
6. Workflow continues from where it left off

This is identical to how Azure Durable Functions and Temporal work.

## Determinism Requirement — Critical

Workflow code must produce the same result on every replay. **Prohibited in workflow orchestrator code:**
- `UUID.randomUUID()`, `Math.random()`
- `LocalDateTime.now()`, `Instant.now()`
- Global mutable state or environment variable reads
- File system access
- Network calls (HTTP, DB, etc.)
- `Thread.sleep()` — use durable timers instead

**All of the above must live in activities.** The SDK provides deterministic equivalents (e.g., `WorkflowContext.getCurrentInstant()` for time).

Updating workflow code for already-running instances requires versioning care — changed code must still produce the same decisions for already-completed steps.

## Features

### Durable Timers

Schedule delays of any duration (minutes to years) without holding memory. Backed by actor reminders. Use for:
- Subscription renewal reminders
- Retry delays
- Long-term scheduled actions

### External Events

Workflows can wait for named external signals (e.g., human approval, external system callback):
```java
// In workflow
Object approval = ctx.waitForExternalEvent("approval").await();

// From external system
workflowClient.raiseEvent(instanceId, "approval", approvalData);
```

### Management Operations

Via HTTP/gRPC APIs or the Java SDK `DaprWorkflowClient`:
- Start a new instance: `scheduleNewWorkflow()`
- Get status: `getInstanceState()`
- Terminate: `terminateWorkflow()`
- Pause/resume: `pauseWorkflow()` / `resumeWorkflow()`
- Purge history: `purgeInstance()`

### Eternal Workflows (Continue-as-New)

For workflows that run forever (event processing loops), use `continueAsNew()` to truncate history and start fresh with new input. This prevents unbounded history growth.

## State Store Compatibility

Not all state stores work as workflow backends. Known limitations:
- **Azure Cosmos DB**: payload size restrictions
- **AWS DynamoDB**: complexity restrictions

Use Redis, PostgreSQL, or SQL Server for reliable workflow support.

## Java SDK

Define a workflow:
```java
public class OrderWorkflow implements Workflow {
    @Override
    public WorkflowStub create() {
        return ctx -> {
            OrderRequest order = ctx.getInput(OrderRequest.class);
            
            // Call activity (non-deterministic work goes here)
            ctx.callActivity("ReserveInventory", order, InventoryResult.class).await();
            
            // Durable timer
            ctx.createTimer(Duration.ofMinutes(30)).await();
            
            // Wait for external event
            boolean approved = ctx.waitForExternalEvent("PaymentApproved", Boolean.class).await();
            
            if (approved) {
                ctx.callActivity("ShipOrder", order).await();
            }
        };
    }
}
```

Define an activity:
```java
public class ReserveInventoryActivity implements WorkflowActivity {
    @Override
    public Object run(WorkflowActivityContext ctx) {
        OrderRequest order = ctx.getInput(OrderRequest.class);
        // Safe to do I/O here
        inventoryService.reserve(order.getItemId(), order.getQuantity());
        return new InventoryResult(true);
    }
}
```

Start a workflow:
```java
DaprWorkflowClient workflowClient = new DaprWorkflowClient();
String instanceId = workflowClient.scheduleNewWorkflow(
    OrderWorkflow.class, 
    new OrderRequest("item-1", 5)
);
```

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Java SDK](dapr-java-sdk.md)
- [Dapr Workflow Patterns](dapr-workflow-patterns.md)
