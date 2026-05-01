# Dapr Actors

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-actors-overview](../../raw/dapr/2026-05-01-dapr-actors-overview.md)
> Updated: 2026-05-01

## Overview

Dapr implements the **Virtual Actor** pattern — a programming model where each actor is a uniquely identified, isolated, stateful, single-threaded unit of computation. Actors are "virtual" because they exist logically at all times; you never explicitly create or destroy them. The runtime activates them on first call and garbage-collects inactive instances from memory while preserving their state persistently.

## When to Use Actors

Actors are ideal when you have:
- **Thousands or more** small, independent, isolated state+logic units (e.g., per-user sessions, per-device state, per-game-entity logic)
- Objects that are **naturally single-threaded** and don't need to call many external components
- Objects that should **not block callers** — avoid long-running I/O inside actor methods

For complex orchestration across multiple services, use [Dapr Workflows](dapr-workflows.md) instead.

## Actor Identity

Every actor is identified by two things:
- **Actor type**: analogous to a class (e.g., `OrderActor`)
- **Actor ID**: a unique string within that type (e.g., order ID, user ID); Dapr auto-generates if not provided

Multiple instances of the same type are independent — `OrderActor/order-123` and `OrderActor/order-456` are separate actors with separate state.

## Lifecycle

1. **Activation**: First call to an actor activates it (loads state, runs `onActivate`)
2. **Active**: Processes calls sequentially
3. **Idle timeout**: After configurable inactivity, garbage-collected from memory; state persists
4. **Reactivation**: Next call reloads state and resumes

Timers reset the idle timer. Reminders will activate an inactive actor when they fire.

## Concurrency — Turn-Based Access

The Dapr actor runtime enforces **turn-based (single-threaded) execution**:
- Only one method, timer callback, or reminder callback executes at a time per actor instance
- No locks or synchronization required in actor code
- Even async methods are non-interleaved — the runtime does not start a new turn until the current one completes

**Reentrancy**: An actor can call methods on itself (configured separately via reentrancy support).

## State

Actor state is stored in a **transactional state store** configured with `actorStateStore: true`. Only one state store can serve all actors in a deployment. Strongly recommended: always set TTL on actor state (`ttlInSeconds`) to ensure eventual cleanup of orphaned state.

State access in Java SDK:
```java
// In actor implementation
ActorStateManager stateManager = getActorStateManager();
stateManager.set("balance", 100.0).block();
Optional<Double> balance = stateManager.get("balance", Double.class).block();
stateManager.remove("balance").block();
```

## Distribution and Placement Service

The **Placement service** manages actor type registration and partitioning across the cluster:
1. Each Dapr sidecar reports its registered actor types to Placement
2. Placement calculates consistent hash ring partitioning
3. Any call to `ActorType/actorId` routes to the same Dapr sidecar (and thus the same actor instance)

This guarantees that there is at most one active instance of a given actor at any time, even in distributed clusters.

During failover, Placement redistributes actors to healthy nodes, reactivating them on their new host.

## Timers

Lightweight, **stateless** periodic callbacks registered against a specific actor instance:
- Fired while the actor is active
- Do **not** survive actor deactivation or restart
- Used for short-lived, low-priority periodic work

## Reminders

Durable, **stateful** periodic callbacks:
- Persisted in the actor state store
- Survive actor deactivation, crashes, and restarts
- Will **reactivate** an inactive actor when fired
- Used for critical periodic work that must not be lost

Choose timers for ephemeral work, reminders for durable work.

## Namespaced Actors

Actor types can be deployed across different namespaces. Invocation is namespace-scoped by default — actors only call other actors in the same namespace unless explicitly configured otherwise.

## Java SDK

Define an actor interface (must extend `Actor`):
```java
public interface OrderActorInterface extends Actor {
    Mono<Void> processOrder(OrderRequest request);
    Mono<Double> getBalance();
}
```

Implement it (extend `AbstractActor`):
```java
public class OrderActor extends AbstractActor implements OrderActorInterface {
    @Override
    public Mono<Void> processOrder(OrderRequest request) { ... }
}
```

Client-side usage:
```java
ActorClient actorClient = new ActorClient();
ActorProxyBuilder<OrderActorInterface> builder =
    new ActorProxyBuilder<>(OrderActorInterface.class, actorClient);
OrderActorInterface actor = builder.build(new ActorId("order-123"));
actor.processOrder(request).block();
```

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Workflows](dapr-workflows.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Java SDK](dapr-java-sdk.md)
