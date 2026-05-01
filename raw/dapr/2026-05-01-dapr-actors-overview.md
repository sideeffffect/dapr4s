# Dapr Actors Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/actors/actors-overview/
> Collected: 2026-05-01
> Published: Unknown

## Core Concept

The actor pattern represents the lowest-level unit of computation where code exists as a self-contained unit receiving and processing messages sequentially without concurrency concerns.

## Actors in Dapr

Dapr implements the Virtual Actor pattern, enabling developers to write actors following the actor model while leveraging platform scalability and reliability. Each actor is a unique instance of an actor type, comparable to how objects instantiate classes. For instance, a calculator actor type could have numerous distributed instances across cluster nodes, each identified by a distinct actor ID.

## Dapr Actors vs. Dapr Workflow

**Key Distinction:** Actors provide stateful, long-running objects with identity, while Workflows offer higher-level abstraction for orchestrating multiple actors and implementing workflow patterns.

### When to Use Actors

Actors suit scenarios involving:
- Thousands or more small, independent, isolated state and logic units
- Single-threaded objects requiring minimal external component interaction
- Actor instances that avoid blocking callers through I/O operations

### When to Use Workflow

Workflows serve complex orchestration needs involving multiple services and components, such as user registration processes, message routing, and error handling workflows.

## Actor Types and IDs

Actors are defined by type (like class definitions) with each instance having a unique actor ID — any string value. Dapr auto-generates random IDs if not provided.

## Key Features

### Namespaced Actors
Supports actor type deployment across different namespaces with same-namespace invocation capability.

### Actor Lifetime
- Automatic activation upon initial requests
- Garbage collection of unused in-memory objects
- Knowledge retention for later reactivation
- State persistence through configured state providers

### Distribution and Failover
Dapr automatically distributes actor instances throughout clusters and migrates them to healthy nodes for reliability.

### Actor Communication
Services invoke actor methods via HTTP through sidecars, which use cached placement information to route calls to appropriate instances.

#### Concurrency — Turn-Based Access
The turn-based access model eliminates threading synchronization needs — methods execute sequentially without concurrent access complications. No more than one thread can be active inside an actor object's code at any time.

### State Management
Transactional state stores maintain actor state. The `actorStateStore` property must be set to `true` in state store component metadata. Only one state store component can serve all actors.

### Timers and Reminders

**Timers:** Lightweight, stateless periodic work scheduling; information doesn't persist post-deactivation.

**Reminders:** Stateful alternatives; persist through Dapr actor state providers; survive deactivation.

## Distribution and Placement Service

The Placement Service manages actor distribution automatically:
1. Service sidecars retrieve registered actor types and configuration
2. The runtime registers available actor types
3. Placement calculates partitioning across instances

When a client calls an actor with a particular id, the Dapr instance hashes the actor type and id, and uses the information to call onto the corresponding Dapr instance. This ensures consistent routing to the same partition for any given actor ID.

## Reentrancy

Actors can invoke methods on themselves through dedicated reentrancy support.
