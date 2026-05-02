# Dapr Pluggable Components

> Sources: Dapr documentation, Unknown
> Raw: [dapr-pluggable-components-overview](../../raw/dapr/2026-05-01-dapr-pluggable-components-overview.md)
> Updated: 2026-05-01

## Overview

Dapr ships with a large library of built-in components (state stores, pub/sub brokers, bindings) implemented in Go and bundled with the runtime. Pluggable components are an extension mechanism that lets teams implement custom components in *any* gRPC-capable language, deploy them as separate processes or containers, and register them with the Dapr sidecar via Unix Domain Sockets — all without forking the Dapr runtime or waiting for an official release cycle.

## Pluggable vs. Built-in Components

| Dimension | Built-in | Pluggable |
|---|---|---|
| **Implementation language** | Go only | Any gRPC-supported language |
| **Execution context** | Inside the Dapr runtime process | Separate process or container |
| **Registration mechanism** | Compiled into codebase | Unix Domain Socket (gRPC) |
| **Release cycle** | Tied to Dapr releases | Independent |
| **Startup** | Automatic | Manual (user-managed) |
| **Typical use case** | Open-source, general-purpose connectors | Proprietary, private, or polyglot connectors |

## Supported Component Interfaces

A pluggable component can implement one or more of these interfaces:

| Type | Notes |
|---|---|
| **State store** | Includes optional transactional and queryable sub-interfaces |
| **Pub/Sub broker** | Standard publish and subscribe |
| **Input binding** | Trigger application from external event |
| **Output binding** | Push data to external system |

## Multi-Interface Components

A single pluggable component can implement multiple interfaces simultaneously. For example, one component binary could serve as both a state store and a pub/sub broker. The Dapr component spec declares which interfaces are active.

While possible, multi-interface components increase internal complexity. The Dapr documentation recommends preferring single-interface components when separation of concerns is feasible.

## Implementation Architecture

Communication between the Dapr sidecar and a pluggable component uses **gRPC over a Unix Domain Socket** (UDS). This avoids network overhead, keeps traffic off the network interface, and allows any gRPC-capable language to host the component.

Implementation steps:

1. **Locate the proto definition** — Dapr publishes the component interface proto files that define the gRPC service contracts.
2. **Generate service scaffolding** — Use the language-appropriate protoc plugin to generate server stubs.
3. **Implement the gRPC service** — Fill in the generated stubs with the custom logic for your backing system.

The component then creates a UDS socket at a path the Dapr sidecar is configured to discover.

## Component Spec (same format as built-in)

Pluggable components use the identical YAML component spec format as built-in components. The only difference is that the `type` field points to a pluggable component identifier, and the spec includes the socket path:

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: my-custom-store
spec:
  type: state.my-pluggable-store   # points to your pluggable component
  version: v1
  metadata:
    - name: socketFolder
      value: "/tmp/dapr-pluggable"
```

## Operational Requirements

Unlike built-in components (which start automatically with `dapr init`), pluggable components must be:

1. **Started before Dapr** (or at least before the first interaction) — the Dapr sidecar discovers the UDS socket on startup.
2. **Kept alive** — the component process must remain running for the sidecar to communicate with it.
3. **Declared in the component spec** — just like built-in components, a YAML spec is required.

In Kubernetes, the component process typically runs as a sidecar container in the same Pod as the application, sharing the Pod's local filesystem for the UDS socket.

## When to Build a Pluggable Component

Consider building a pluggable component when:

- The target system is proprietary and cannot be open-sourced.
- The team prefers a language other than Go (Java, Python, Rust, etc.).
- The component should be versioned and released independently from Dapr.
- Custom middleware, transformation, or routing logic is needed at the component boundary.

## See Also

- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Overview](dapr-overview.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Pub/Sub](dapr-pub-sub.md)
- [Dapr Other Building Blocks](dapr-other-building-blocks.md)
