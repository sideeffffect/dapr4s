# Dapr Service Invocation

> Sources: Dapr Documentation, 2026-04-20
> Raw: [dapr-service-invocation-overview](../../raw/dapr/2026-05-01-dapr-service-invocation-overview.md)
> Updated: 2026-05-01

## Overview

Dapr's service invocation building block enables reliable, secure, observable service-to-service calls using standard HTTP or gRPC. Applications address other services by **App ID** rather than IP address or hostname. The Dapr sidecar handles service discovery, mTLS encryption, retries with backoff, distributed tracing, and load balancing automatically — the calling application makes a simple localhost request.

## Call Flow

```
App A → Dapr sidecar A (HTTP/gRPC) → Dapr sidecar B (gRPC) → App B
                                   ↑
                         name resolution component
                         (mDNS, Kubernetes DNS, Consul, SQLite)
```

Key points:
- App-to-sidecar: HTTP or gRPC (developer choice)
- Sidecar-to-sidecar: always gRPC (internal optimization)
- Service discovery is swappable per environment

## Service Discovery Options

| Environment | Component |
|---|---|
| Kubernetes | Kubernetes DNS |
| Self-hosted (single machine) | mDNS |
| Self-hosted (single node, local dev) | SQLite |
| Multi-machine / HashiCorp | Consul |

## Addressing

Call format (HTTP):
```
http://localhost:<dapr-http-port>/v1.0/invoke/<app-id>/method/<method-name>
```

Or using the `dapr-app-id` header on existing HTTP services (no URL change required).

Cross-namespace: `<app-id>.<namespace>` as the target.

## Core Features

**mTLS Security**: All sidecar-to-sidecar traffic is encrypted and mutually authenticated via the Sentry CA. No code changes required.

**Retries with Backoff**: Automatic retry for transient failures. Exception: streaming requests (chunked transfer encoding or unknown `Content-Length`) are never retried because the body cannot be replayed.

**Load Balancing**: Round-robin across multiple instances of the target App ID using mDNS (self-hosted) or Kubernetes load balancing.

**Access Control**: Define policies specifying which App IDs can call which endpoints and which HTTP methods. Enforced at the sidecar level.

**Distributed Tracing**: Every invocation automatically generates W3C-compatible distributed trace spans.

**Namespace Scoping**: Services in different namespaces communicate with proper isolation.

## Streaming Support

Streaming is supported for large payloads:
- Bodies are not buffered in memory on either sidecar
- Server-sent events and file downloads stream end-to-end
- Retry policies are automatically skipped for streaming requests
- Circuit breakers still apply to streaming connections

## Non-Dapr HTTP Endpoints

Service invocation can also target non-Dapr HTTP services (no sidecar required on the target). This enables gradual Dapr adoption or integration with external services.

## SDK Note (Java)

The Java SDK's `DaprClient.invokeMethod()` is **deprecated**. The Dapr project recommends using native HTTP clients (e.g., `java.net.http.HttpClient`) or native gRPC clients directly for service invocation, passing the `dapr-app-id` header yourself. This gives more control and avoids the SDK overhead.

## Implementation Approaches

1. **Header injection**: Add `dapr-app-id: <target-app-id>` header to existing HTTP clients targeting `http://localhost:3500`
2. **Direct URL**: Change service URLs to `http://localhost:3500/v1.0/invoke/<app-id>/method/<path>`
3. **gRPC**: Configure Dapr as the gRPC proxy (no SDK needed)
4. **Dapr CLI**: `dapr invoke --app-id <app-id> --method <method>` for testing

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Java SDK](dapr-java-sdk.md)
