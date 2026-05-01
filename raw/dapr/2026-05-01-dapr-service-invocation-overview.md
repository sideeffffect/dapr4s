# Dapr Service Invocation Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/service-invocation/service-invocation-overview/
> Collected: 2026-05-01
> Published: 2026-04-20

## Introduction

The service invocation API enables applications to communicate reliably and securely using standard gRPC or HTTP protocols. It addresses fundamental microservice challenges including service discovery, standardizing API calls, securing inter-service communication, handling failures through retries, and implementing observability.

## Key Challenge Areas Addressed

Microservice developers face several obstacles:
- **Service discovery**: Locating different services
- **API standardization**: Invoking methods between services consistently
- **Secure communication**: Encrypting calls and enforcing access control
- **Failure mitigation**: Managing timeouts, retries, and transient errors
- **Observability**: Implementing tracing and metrics for production diagnostics

## Service Invocation Architecture

The system operates through a sidecar pattern where:
1. Service A makes an HTTP or gRPC call to its local Dapr sidecar
2. Dapr discovers Service B's location using name resolution components
3. Dapr forwards the message to Service B's sidecar over gRPC
4. Service B's sidecar delivers the request to the target service
5. Service B executes business logic and returns responses
6. Dapr forwards responses back through the chain
7. Service A receives the final response

**Note**: Calls between Dapr sidecars use gRPC for performance; only service-to-sidecar communication supports HTTP or gRPC options.

## Core Features

### HTTP and gRPC Support
- **HTTP**: Minimal setup required; add the `dapr-app-id` header to existing endpoints
- **gRPC**: Native support for existing gRPC applications without SDK modifications

### Service-to-Service Security
The Dapr Sentry service provides mutual TLS authentication across hosted platforms with automatic certificate rollover.

### Resiliency and Retries
Automatic retry mechanisms with backoff periods handle transient failures. However, streaming requests with chunked transfer encoding or unknown `Content-Length` headers bypass retries since request bodies cannot be replayed.

### Built-in Observability
All inter-service calls generate distributed traces and metrics automatically, providing call graphs and diagnostics essential for production environments.

### Access Control Policies
Applications control which services can invoke them and what operations are permitted, enabling soft multi-tenancy deployments when combined with secure communication.

### Namespace Scoping
Services deployed across different namespaces can communicate with proper scope isolation.

### Load Balancing
Round-robin load balancing distributes requests across multiple application instances using mDNS, whether instances run on single or networked machines.

### Swappable Service Discovery
Dapr supports multiple name resolution components:
- Kubernetes DNS for cluster environments
- mDNS for self-hosted single machines
- SQLite for local development on single nodes
- Consul for multi-machine deployments

## Streaming for HTTP Service Invocation

The system implements six-step data flow for streaming:
1. Request from App A to Dapr sidecar A
2. Request from Dapr sidecar A to Dapr sidecar B
3. Request from Dapr sidecar B to App B
4. Response from App B to Dapr sidecar B
5. Response from Dapr sidecar B to Dapr sidecar A
6. Response from Dapr sidecar A to App A

**Streaming Benefits**:
- Request and response bodies avoid memory buffering
- Large payloads process without excessive memory consumption
- Server-sent events and file downloads stream directly
- Retry policies automatically bypass for streaming requests
- Circuit breakers continue monitoring failures

## Non-Dapr Endpoint Invocation

The API supports calling non-Dapr HTTP endpoints, enabling partial Dapr adoption or integration with external services where code modifications aren't feasible.

## Implementation Methods

1. **HTTP with `dapr-app-id` header**: Simplest approach for existing HTTP services
2. **gRPC configuration**: Use gRPC server with Dapr CLI invocation
3. **Direct API calls**: Update address URLs to `localhost:<dapr-http-port>`
4. **SDK integration**: Language-specific Dapr client libraries
5. **Dapr CLI**: Quick testing via `dapr invoke --method <method-name>` commands
