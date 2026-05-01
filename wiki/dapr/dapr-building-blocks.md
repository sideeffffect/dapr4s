# Dapr Building Blocks

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-building-blocks-concept](../../raw/dapr/2026-05-01-dapr-building-blocks-concept.md); [dapr-overview](../../raw/dapr/2026-05-01-dapr-overview.md)
> Updated: 2026-05-01

## Overview

Dapr's 13 building blocks are independent HTTP/gRPC API endpoints exposed by the sidecar that encapsulate common distributed systems patterns. Each building block solves a specific class of problem; applications use only the ones they need. Building blocks are backed by pluggable **components** (e.g., Redis for state, Kafka for pub/sub) that can be swapped without code changes.

## Service Invocation (`/v1.0/invoke`)

Enables reliable, secure service-to-service calls with built-in service discovery, retries, mTLS, and distributed tracing. Callers address services by App ID rather than IP. Sidecar-to-sidecar traffic uses gRPC; application-to-sidecar can be HTTP or gRPC. **Note**: The Java SDK's `invokeMethod()` is deprecated — prefer native HTTP/gRPC clients directly.

See: [Dapr Service Invocation](dapr-service-invocation.md)

## Publish/Subscribe (`/v1.0/publish`, `/v1.0/subscribe`)

Asynchronous event-driven messaging through pluggable brokers (Kafka, RabbitMQ, Azure Service Bus, etc.). Messages are wrapped in CloudEvents 1.0 envelopes. Provides at-least-once delivery. Three subscription styles: declarative (YAML), programmatic (code), or streaming (runtime). Consumer groups are automatic — all replicas with the same App ID share messages.

See: [Dapr Pub/Sub](dapr-pub-sub.md)

## State Management (`/v1.0/state`)

Key/value storage with pluggable backends. Features: optimistic concurrency via ETags, strong/eventual consistency modes, bulk and transactional operations, TTL, automatic state encryption, shared state across applications, and the transactional outbox pattern. Actor state uses the same store with `actorStateStore: true`.

See: [Dapr State Management](dapr-state-management.md)

## Actors (`/v1.0/actors`)

Virtual actor pattern — isolated, stateful compute units with turn-based (single-threaded) access. Actors are distributed by the Placement service, automatically activated on first call, and garbage-collected when idle. Support timers (ephemeral) and reminders (durable/persistent). Best for thousands of small, independent, isolated units.

See: [Dapr Actors](dapr-actors.md)

## Workflows (`/v1.0/workflow`)

Long-running, fault-tolerant workflow orchestration built on top of actors. Uses event sourcing (append-only history) for durability and replay. Workflow code must be deterministic (no I/O, randomness, or time calls — use activities for those). Supports child workflows, durable timers, external events, and multi-app spanning.

See: [Dapr Workflows](dapr-workflows.md)

## Bindings (`/v1.0/bindings`)

Bidirectional connector to external systems without requiring vendor SDKs. **Input bindings** trigger the application when external events occur (e.g., a message arrives in a queue). **Output bindings** allow the application to invoke external systems. Common operations: create, update, delete, exec.

## Secrets (`/v1.0/secrets`)

Unified API for retrieving secrets from any configured secret store (AWS Secrets Manager, Azure Key Vault, HashiCorp Vault, Kubernetes secrets, local files). Secrets can also be referenced inside component YAML files to avoid embedding credentials. Scope policies restrict which apps access which secrets.

## Configuration (`/v1.0/configuration`)

Read-only key/value configuration items from a configuration store, with subscription support for real-time change notifications. Distinct from Dapr's own operational configuration (which controls sidecar behavior). Updates are managed externally (ops tooling), not through this API.

## Distributed Lock (`/v1.0-alpha1/lock`) — Alpha

Named mutex locks with lease-based automatic expiration. Prevents deadlocks from crashed holders. Use cases: exclusive database row access, sequential queue processing, shared resource coordination. Only one instance can hold a named lock at a time.

## Cryptography (`/v1.0-alpha1/crypto`) — Alpha

Encrypt/decrypt without application ever seeing raw key material. Two component types: vault-backed (Azure Key Vault — operations happen inside the vault) and Dapr-engine-backed (operations in the sidecar, keys stored separately). Supports RSA and AES, stream processing for large files. gRPC preferred over HTTP.

## Jobs (`/v1.0-alpha1/jobs`) — Alpha

Schedule jobs for future execution (at-least-once guarantee, never before schedule time). Backed by the Scheduler control plane service with embedded Etcd. Use cases: database backups, ETL, email notifications, batch financial processing. Multi-replica aware — only one Scheduler triggers each job.

## Conversation (`/v1.0-alpha2/conversation`) — Alpha

Abstraction layer for LLM interactions. Features: prompt caching, response formatting, PII obfuscation. Decouples application code from specific LLM provider SDKs.

## Observability (Cross-cutting)

Not a standalone API but a cross-cutting concern. All building block calls automatically emit distributed traces (W3C TraceContext compatible), metrics, and logs. Works with OpenTelemetry, Zipkin, Prometheus, and Grafana without application code changes.

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Service Invocation](dapr-service-invocation.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Pub/Sub](dapr-pub-sub.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Workflows](dapr-workflows.md)
- [Dapr Other Building Blocks](dapr-other-building-blocks.md)
