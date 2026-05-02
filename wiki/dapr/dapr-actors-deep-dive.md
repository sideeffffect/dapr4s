# Dapr Actors Deep Dive

> Sources: Diagrid, Unknown
> Raw: [dapr-actors-deep-dive](../../raw/dapr/2026-05-01-dapr-actors-deep-dive.md)
> Updated: 2026-05-01

## Overview

Dapr Actors implement the *virtual actor model* originated by Microsoft Research (2008), built on the classic actor model (Carl Hewitt, 1973). The key insight of the virtual model is that actors are never explicitly created or destroyed — they are logically always present. The runtime activates them on demand and deactivates them when idle, persisting state to external storage in between. This gives developers OOP-style programming with cloud-scale distribution, without reasoning about placement, lifecycle, or concurrency.

## Four Foundational Properties

Every Dapr Actor has:

| Property | Meaning |
|---|---|
| **Identity** | Uniquely addressable by `(actorType, actorId)` tuple |
| **Computation** | Methods and behaviors that respond to messages |
| **State** | Internal, durable, long-lived data storage |
| **Communication** | Message-based interaction; no shared memory |

## Virtual Actor Lifecycle

### Activation

When a caller addresses an actor that is not currently in memory, the Placement Service uses consistent hashing on `(actorType, actorId)` to determine which service instance should host it, then the Dapr sidecar on that node creates the actor in memory. `OnActivateAsync` (or equivalent) fires once per activation.

### Deactivation

After a configurable inactivity period, the Dapr runtime deactivates the actor: state is flushed to the external state store, and the in-memory instance is garbage collected. The actor can be reactivated later — potentially on a different node — with no developer intervention required.

### Reactivation

On the next call, the process repeats: consistent hash picks a node, state is loaded from the store, and the actor is live again. Migrations during cluster rescaling or node failure happen transparently.

**Critical implication:** never store important data in constructor fields or in-memory variables that are not persisted via the state manager. An actor can deactivate at any time.

## Turn-Based Concurrency (Single-Threaded Access)

The Dapr runtime serializes all calls to a single actor instance. Only one method executes at a time per actor — no locks required, no race conditions possible. This is called the *turn-based access model*.

Consequences:
- Actor logic can be written without synchronization primitives.
- Highly concurrent access to the *same actor instance* creates a queue (bottleneck risk for "hot" actors).
- Access to *different actor instances* is fully parallel.

## State Persistence Architecture

State is managed through a two-tier model:

1. **In-memory cache** — fast reads during an activation; writes go to the state manager which batches them.
2. **External state store** — the durable backing (configurable: Redis, Cosmos DB, DynamoDB, etc.). Every `StateManager.SetState` call is ultimately a remote write.

Best practices:
- Keep per-actor state small and focused on entity-specific data.
- Avoid storing large blobs, logs, or cross-entity aggregations.
- Every write has network latency cost; batch writes when possible.
- Design methods to be idempotent so retries after failures are safe.

## Timers vs. Reminders

Both schedule future callbacks, but they differ on durability:

| Dimension | Timers | Reminders |
|---|---|---|
| **Persistence** | In-process only | Durable, stored by Dapr Scheduler |
| **Survives deactivation** | No — lost when actor deactivates | Yes — fires even if actor is deactivated or migrated |
| **Survives restart** | No | Yes |
| **Use case** | Ephemeral, short-lived tasks within an activation | Business-critical scheduled work |

Both are coordinated by the **Scheduler service**, which ensures exactly-once delivery regardless of actor placement changes.

```csharp
// Register a durable reminder on activation
protected override async Task OnActivateAsync()
{
    await RegisterReminderAsync(
        "AutoDimReminder",
        null,
        TimeSpan.FromSeconds(20),   // due time
        TimeSpan.FromSeconds(2)     // repeat period
    );
}

// Implement IRemindable to receive reminder callbacks
public async Task ReceiveReminderAsync(string name, byte[] data, TimeSpan period)
{
    if (name == "AutoDimReminder")
    {
        if (_state.Brightness > 0) _state.Brightness -= 5;
        await StateManager.SetStateAsync("light-state", _state);
    }
}
```

## Placement Service

The Placement Service is the control-plane component responsible for actor routing:

1. Each Dapr sidecar registers the actor types hosted by its application.
2. The service builds a global `actorType → service instance` mapping.
3. Consistent hashing ensures that for any `(actorType, actorId)`, only one partition (and thus one node) is responsible.
4. The full partition table is pushed to all sidecars for local, low-latency routing decisions.
5. On rescale or node failure, the Placement Service rebalances and redistributes the partition table.

This ensures deterministic routing: identical actor IDs always route to the same logical partition.

## Reentrancy

By default, Dapr Actors are *not reentrant*: if actor A calls actor B, and B tries to call back into A, the call will deadlock because A's turn-based queue is blocked waiting for B. Dapr supports optional reentrancy configuration that breaks this restriction for specific cross-actor call patterns, but reentrancy adds complexity and should be enabled only when necessary.

## Actor Hosting and Registration

```csharp
var app = WebApplication.CreateBuilder(args)
    .AddActor<Light>()
    .Build();

app.MapActorsHandlers();
app.Run();
```

Once registered, the Dapr sidecar handles all distribution, discovery, and lifecycle management.

## Scalability Characteristics

The virtual model scales by separating identity from memory:

- Millions of logical actor instances can exist while only actively-used ones occupy memory.
- Actors redistribute automatically during scale-out or node failure.
- Adding nodes increases the available capacity for concurrent actor activations.
- The Placement Service's consistent hashing re-partitions gracefully.

Real-world validation: Schréder Hyperion manages millions of smart city lights using Dapr Actors on Kubernetes.

## Optimal and Sub-Optimal Use Cases

| Ideal | Poor Fit |
|---|---|
| Many small, independent entities (IoT, shopping carts, game players) | Mostly stateless services |
| Long-running state with per-entity isolation | "Hot" endpoints needing high concurrency on the same entity |
| AI agents with persistent reasoning state | Strong consistency across multiple entities |
| Workflows needing durable step execution | Large-scale streaming workloads |

## Actor-to-Actor Communication

Actors invoke each other via the service invocation API:

```csharp
var otherActor = client.CreateActorProxy<ILight>("light", "light-43");
await otherActor.SetBrightness(50);
```

Calls look local but are routed through the Dapr mesh. Long actor-to-actor call chains create tight coupling and can amplify latency; prefer events or workflows for loosely-coupled orchestration.

## Integration with Dapr Workflows

Workflows are built *on top of* actors:

- **Workflow Actor**: one per workflow instance; maintains the event-sourced execution history and drives deterministic replay.
- **Activity Actor**: short-lived; created per activity task, executes the work, returns the result to the parent workflow actor, then deactivates.

This means Dapr Workflow inherits actor durability guarantees without the developer needing to manage actors directly.

## Integration with AI Agents

Dapr Agents extend the workflow/actor model for AI: long-running agents that call LLM tools and maintain reasoning state are natural fits for the virtual actor model. State persists across reasoning steps; reminders can wake agents on schedule; turn-based access prevents concurrent tool calls from corrupting agent state.

## See Also

- [Dapr Actors](dapr-actors.md)
- [Dapr Workflows](dapr-workflows.md)
- [Dapr Workflow Patterns](dapr-workflow-patterns.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Java SDK](dapr-java-sdk.md)
