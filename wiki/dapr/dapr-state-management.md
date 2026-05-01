# Dapr State Management

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-state-management-overview](../../raw/dapr/2026-05-01-dapr-state-management-overview.md)
> Updated: 2026-05-01

## Overview

Dapr's state management building block provides a key/value store API backed by pluggable components (Redis, PostgreSQL, MongoDB, Azure Cosmos DB, DynamoDB, and many more). Applications save, read, query, and delete state through the sidecar API without caring about the underlying store technology. The store can be swapped by changing a YAML component file — no code changes required.

## API Endpoint

```
/v1.0/state/<store-name>
```

Operations: GET (read), POST (save/query), DELETE (remove), POST `/v1.0/state/<store>/transaction` (transactional batch).

## Concurrency Control

Dapr uses **Optimistic Concurrency Control (OCC)** via ETags:
- Every state read returns an ETag
- Writes/deletes can include the ETag; if it doesn't match (another writer updated first), the request is rejected with a conflict error
- Without an ETag: **last-write-wins** (default behavior)

Recommendation: assume eventual consistency and last-write-wins by default; adopt ETags when your business logic requires conflict detection.

## Consistency Modes

| Mode | Behavior |
|---|---|
| Eventual (default) | Returns once the primary store accepts the write |
| Strong | Waits for replica acknowledgment before confirming |

Not all backends support strong consistency — query the store's metadata capabilities at runtime.

## Bulk and Transactional Operations

- **Bulk reads** (`getBulkState`): retrieve multiple keys in one call
- **Bulk saves** (`saveBulkState`): save multiple items in one call
- **Transactions** (`executeStateTransaction`): atomic batch of writes, updates, and deletes that succeed or fail as a unit (requires a transactional store)

## Key Scoping

By default, state keys are prefixed with the App ID to prevent collisions between applications sharing a state store: `<app-id>||<key>`. This can be disabled to enable intentional state sharing.

## State Sharing Between Applications

Three modes:
1. **App-isolated** (default): `<app-id>||<key>` prefix, no cross-app access
2. **Shared within a store**: disable the prefix; multiple apps read/write the same keys
3. **Cross-store**: apps can use different stores entirely

## Time-to-Live (TTL)

Per-key expiration: set `ttlInSeconds` metadata on save. The store automatically removes expired entries. For actor state, TTL should always be set.

## State Encryption

Dapr supports automatic **client-side encryption** of state values using AES-256. Encryption happens at the SDK/API layer before reaching the sidecar — the store never sees plaintext. Key rotation is supported without data loss.

## Transactional Outbox Pattern

Dapr enables the outbox pattern: a single atomic transaction spanning a transactional state store write AND a message broker publish. This guarantees exactly-once semantics between state changes and events.

## State Query API

Optional capability (not all stores support it): filter, sort, and paginate state without writing store-specific queries. Uses Dapr's own query syntax independent of the backend.

## Direct Store Queries

Applications can query the underlying store directly (bypassing Dapr) for read-only analytics — e.g., Redis `KEYS` commands or SQL `SELECT`. Direct queries are **not** governed by Dapr's concurrency control or ETag tracking.

Actor state schema:
```sql
SELECT * FROM StateTable WHERE Id='<app-id>||<actor-type>||<actor-id>||<key>'
```

## Java SDK Usage

```java
// Save state
client.saveState("statestore", "mykey", myObject).block();

// Read state
State<MyType> state = client.getState("statestore", "mykey", MyType.class).block();

// Save with ETag (OCC)
StateOptions options = new StateOptions(StateOptions.Consistency.STRONG,
    StateOptions.Concurrency.FIRST_WRITE);
client.saveState("statestore", "mykey", state.getEtag(), newValue, options).block();

// Transactional batch
List<TransactionalStateOperation<?>> ops = List.of(
    new TransactionalStateOperation<>(TransactionalStateOperation.OperationType.UPSERT,
        new State<>("key1", value1, "")),
    new TransactionalStateOperation<>(TransactionalStateOperation.OperationType.DELETE,
        new State<>("key2"))
);
client.executeStateTransaction("statestore", ops).block();
```

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Java SDK](dapr-java-sdk.md)
