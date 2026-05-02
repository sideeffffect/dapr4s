# Dapr Pluggable Components Overview

> Source: https://docs.dapr.io/developing-applications/develop-components/pluggable-components/pluggable-components-overview/
> Collected: 2026-05-01
> Published: Unknown

## What Are Pluggable Components?

Pluggable components represent an alternative to built-in components, offering independent deployment and registration. They are "components that are not included as part of the runtime, as opposed to the built-in components included with `dapr init`."

## Key Differences from Built-in Components

| Aspect | Built-in | Pluggable |
|--------|----------|-----------|
| **Language** | Go only | Any gRPC-supported language |
| **Execution** | Part of Dapr runtime | Separate process/container |
| **Registration** | Included in codebase | Via Unix Domain Sockets (gRPC) |
| **Distribution** | With Dapr releases | Independent cycle |
| **Activation** | Automatic | Manual (user-started) |

## When to Build Pluggable Components

Organizations should consider pluggable components when:
- Creating proprietary or private components
- Maintaining separation from Dapr's release schedule
- Preferring non-Go implementations for development

## Supported Component Types

Pluggable components can implement interfaces for:
- **State stores** (including transactional and queryable variants)
- **Pub/Sub brokers**
- **Input/Output bindings**
- **Multiple interfaces simultaneously** within a single component

## Implementation Architecture

Creating a pluggable component requires three steps:

1. Locate the proto definition file
2. Generate service scaffolding
3. Implement the gRPC service

The component communicates with Dapr via gRPC over Unix Domain Sockets, eliminating the need for direct integration into the runtime.

## Operational Requirements

Unlike built-in components, pluggable components require:
- A component specification (shared with built-in components)
- Manual startup before Dapr interaction
- Explicit registration facilitation through socket-based communication

## Multi-Interface Components

A single pluggable component can simultaneously implement multiple interfaces: "a single pluggable component can simultaneously function as a state store, pub/sub, and input or output binding." However, this approach increases complexity; simpler separation of concerns is generally recommended.
