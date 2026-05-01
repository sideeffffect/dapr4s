# Dapr Overview — Distributed Application Runtime

> Source: https://docs.dapr.io/concepts/overview/
> Collected: 2026-05-01
> Published: Unknown

## Core Purpose

Dapr is a portable, event-driven runtime designed to simplify microservice development. It makes it easy for any developer to build resilient, stateless, and stateful applications that run on the cloud and edge.

## Key Principles

**Language and Framework Agnostic**: Developers can build applications using their preferred programming language and framework without being locked into specific technologies.

**Platform Independence**: Dapr applications run seamlessly across local machines, Kubernetes clusters, virtual machines, and edge environments.

## Microservice Building Blocks

Dapr provides 13 independent building block APIs:

1. **Service Invocation** - Remote method calls with built-in retries
2. **Publish/Subscribe** - Event-driven messaging with guaranteed delivery
3. **Workflows** - Long-running persistent processes across microservices
4. **State Management** - Key-value storage with pluggable backends
5. **Resource Bindings** - Integration with external systems (databases, queues, filesystems)
6. **Actors** - Stateful objects with concurrency management
7. **Secrets Management** - Secure credential retrieval
8. **Configuration** - Dynamic application settings
9. **Distributed Lock** - Resource coordination
10. **Cryptography** - Encryption/decryption operations
11. **Jobs** - Scheduled task execution
12. **Conversation** - Large Language Model interactions
13. **Observability** - Monitoring, tracing, and logging

## Architecture Model

Dapr operates as a **sidecar** — either a separate process or container running alongside application code. This design eliminates the need to embed runtime logic directly into applications, improving maintainability and enabling polyglot development.

## Hosting Options

- **Self-Hosted**: Local development on Windows/Linux/macOS
- **Kubernetes**: Container orchestration with automatic sidecar injection
- **Physical/Virtual Machines**: High-availability control plane deployment

## Developer Support

Dapr SDKs are available for Go, Java, JavaScript, .NET, PHP, and Python. Framework integrations exist for ASP.NET Core, Spring Boot, Flask, and Express.

## Security and Operations

The platform includes built-in support for mutual TLS encryption, certificate management through the Sentry service, and comprehensive observability through distributed tracing and metrics collection.
