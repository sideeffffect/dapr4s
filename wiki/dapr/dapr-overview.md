# Dapr Overview

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-overview](../../raw/dapr/2026-05-01-dapr-overview.md); [dapr-building-blocks-concept](../../raw/dapr/2026-05-01-dapr-building-blocks-concept.md); [dapr-components-concept](../../raw/dapr/2026-05-01-dapr-components-concept.md); [dapr-sidecar](../../raw/dapr/2026-05-01-dapr-sidecar.md); [dapr-security-concepts](../../raw/dapr/2026-05-01-dapr-security-concepts.md)
> Updated: 2026-05-01

## Overview

Dapr (Distributed Application Runtime) is a portable, event-driven runtime that makes it easy for any developer to build resilient, stateless, and stateful microservice applications that run on the cloud and edge. It is language- and framework-agnostic, exposes capabilities through standard HTTP/gRPC APIs, and deploys as a sidecar alongside application code rather than embedding in the application itself.

## The Sidecar Pattern

Dapr's core architectural unit is the **sidecar** — a separate process called `daprd` that runs alongside the application. The application communicates with its local sidecar over localhost (HTTP on port 3500 or gRPC on port 50001), and the sidecar handles all distributed systems concerns: service discovery, retries, mTLS, tracing, and component integration.

Deployment modes:
- **Self-hosted**: `dapr run` launches `daprd` alongside the application process (local dev)
- **Kubernetes**: The `dapr-sidecar-injector` automatically injects `daprd` into pods annotated with `dapr.io/enabled: "true"`
- **Physical/virtual machines**: Manual `daprd` launch with high-availability control plane

The sidecar exposes three API surfaces: Building Block APIs (application logic), Metadata API (capability discovery), and Health API (readiness/liveness).

## Architecture Layers

```
Application code
      |
  Dapr sidecar (daprd)  ←— Building Block APIs (HTTP/gRPC)
      |
  Components           ←— pluggable backends (Redis, Kafka, Cosmos DB, ...)
```

**Building blocks** are the logical capabilities (state, pub/sub, actors, etc.). Each is an HTTP or gRPC API endpoint on the sidecar.

**Components** are the concrete implementations backing building blocks. They are defined as YAML files and can be swapped without code changes. Components can be:
- **Built-in**: ship with Dapr, community-contributed
- **Pluggable**: privately hosted, outside the runtime (for IP-sensitive or proprietary implementations)

Components support **hot reloading** when enabled — updated without restarting the runtime.

## Building Blocks (13 total)

| Building Block | API Endpoint | Status |
|---|---|---|
| Service Invocation | `/v1.0/invoke` | Stable |
| Publish/Subscribe | `/v1.0/publish`, `/v1.0/subscribe` | Stable |
| State Management | `/v1.0/state` | Stable |
| Actors | `/v1.0/actors` | Stable |
| Workflows | `/v1.0/workflow` | Stable |
| Bindings | `/v1.0/bindings` | Stable |
| Secrets | `/v1.0/secrets` | Stable |
| Configuration | `/v1.0/configuration` | Stable |
| Distributed Lock | `/v1.0-alpha1/lock` | Alpha |
| Cryptography | `/v1.0-alpha1/crypto` | Alpha |
| Jobs | `/v1.0-alpha1/jobs` | Alpha |
| Conversation (LLM) | `/v1.0-alpha2/conversation` | Alpha |
| Observability | (cross-cutting) | Stable |

## Application Identity

Every Dapr-enabled application has a unique **App ID** — the atomic unit of identity. The App ID:
- Drives service discovery (other services invoke by App ID, not IP)
- Scopes state store keys by default (to prevent collisions)
- Drives access control policies
- Cannot contain dots (`.`)

Multiple replicas of the same application share one App ID, forming a consumer group for pub/sub automatically.

## Security Model

Dapr enforces security by default:
- **mTLS on by default** between all sidecars — no configuration needed
- **Sentry service** acts as the Certificate Authority, issuing 24-hour workload certificates with automatic rotation
- **API token authentication** between application and its sidecar (localhost, but still authenticated)
- **Access control lists**: restrict which App IDs can invoke which endpoints
- **Topic scoping**: restrict which apps can publish/subscribe to which topics
- **Secret scoping**: restrict which apps can read which secrets
- **API allow lists**: disable unused building block APIs

Security audits: Cure53 (2020, 2021), Ada Logics (2023 fuzzing + comprehensive). Zero critical/high CVEs as of Feb 2021.

## SDK Support

Official SDKs: Go, Java, JavaScript, .NET, PHP, Python. Framework integrations: ASP.NET Core, Spring Boot, Flask, Express.

## See Also

- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Service Invocation](dapr-service-invocation.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Pub/Sub](dapr-pub-sub.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Workflows](dapr-workflows.md)
- [Dapr Java SDK](dapr-java-sdk.md)
