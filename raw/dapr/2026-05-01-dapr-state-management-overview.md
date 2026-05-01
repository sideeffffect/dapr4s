# Dapr State Management Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/state-management/state-management-overview/
> Collected: 2026-05-01
> Published: Unknown

## Overview

Applications can leverage Dapr's state management API to save, read, and query key/value pairs in the supported state stores. This enables developers to build stateful, long-running applications that persist and retrieve state like shopping carts or game sessions.

The API supports three basic operations:
- **HTTP POST** for saving or querying key/value pairs
- **HTTP GET** for reading specific keys
- State management through pluggable backend components

## Core Features

### Pluggable State Stores

Dapr data stores are modeled as components, which can be swapped out without any changes to your service code. The platform maintains extensive support for various backend technologies including Redis, Azure Cosmos DB, MongoDB, PostgreSQL, DynamoDB, and many others.

### Configurable State Store Behaviors

Applications can specify metadata describing how operations should be handled:

**Concurrency Control:**
- Dapr implements Optimistic Concurrency Control (OCC) using ETags
- When requesting state, systems always attach an ETag property to the returned state
- Updates require matching ETags; deletions use the `If-Match` header
- Mismatched ETags may cause a request rejection

**Consistency Models:**
- **Strong consistency**: waits for replica acknowledgment before confirming writes
- **Eventual consistency**: returns once the underlying store accepts the request (default behavior)

### Content Type Handling

State store components may maintain and manipulate data differently, depending on the content type. Setting content type is optional; developers pass this information via:
- HTTP API: URL query parameter `metadata.contentType`
- gRPC API: key/value pair in request metadata

### Multiple Operations

**Bulk Operations:** Group multiple read requests submitted individually to the data store.

**Transactional Operations:** Atomic transaction grouping write, update, and delete operations that succeed or fail as a unit.

### Actor State Management

Transactional state stores can be used to store actor state. Developers designate a state store by setting `actorStateStore` to `true` in component metadata. Actor state uses a specific schema enabling consistent querying across a single designated state store.

**TTL for Actor State:** You should always set the TTL metadata field (`ttlInSeconds`) when saving actor state to ensure eventual removal.

### State Encryption

Dapr supports automatic client encryption of application state with support for key rotations across all state stores.

### Shared State Between Applications

State can be:
- Isolated to individual applications
- Shared within a single state store between multiple applications
- Shared across different state stores

### Transactional Outbox Pattern

Dapr enables developers to use the outbox pattern for achieving a single transaction across a transactional state store and any message broker.

### Querying State

**State Query API:** Optional Dapr-provided query interface enabling filtering, sorting, and pagination regardless of underlying technology.

**Direct Store Queries:** Applications can query underlying stores natively (e.g., Redis `KEYS` commands) for read-only aggregate operations. Direct queries of the state store are not governed by Dapr concurrency control.

**Actor State Queries:** SQL-capable stores support queries like:
```
SELECT * FROM StateTable WHERE Id='<app-id>||<actor-type>||<actor-id>||<key>'
```

### State Time-to-Live (TTL)

Dapr enables per state set request time-to-live (TTL) allowing applications to set expiration per stored state.

## Key Architectural Principles

By default, your application should assume a data store is eventually consistent and uses a last-write-wins concurrency pattern. Not all stores are created equal, so applications should query the metadata capabilities of the store and make their code adaptive.
