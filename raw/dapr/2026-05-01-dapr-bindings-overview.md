# Dapr Bindings Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/bindings/bindings-overview/
> Collected: 2026-05-01
> Published: Unknown

## Main Concept

Dapr's bindings API enables applications to trigger on external events and interface with external systems. The technology eliminates complexity around messaging systems, allowing developers to focus on business logic rather than implementation details.

## Key Benefits

The bindings building block provides several advantages:

- Removes messaging system connection and polling complexities
- Enables environment-specific configurations without code modifications
- Handles retry logic and failure recovery automatically
- Allows runtime switching between different bindings
- Keeps applications free from third-party SDKs

## Input Bindings

Input bindings trigger application methods when external resource events occur. Implementation requires:

1. **YAML Component Definition** - Specifies binding type and metadata
2. **Event Listener** - Uses HTTP endpoints or gRPC proto library

The system sends OPTIONS requests during startup to all defined input bindings, expecting 2xx or 405 status codes for subscription confirmation.

## Output Bindings

Output bindings invoke external resources with optional payloads and metadata. The process involves:

1. **YAML Component Configuration** - Describes binding type and connection information
2. **Invocation Method** - HTTP endpoint or gRPC method calls
3. **Operation Specification** - Common operations include "create," "update," "delete," and "exec"

## Binding Directions

The optional `direction` metadata field reduces lifecycle dependencies between Dapr sidecars and applications. Supported directions are:

- "input"
- "output"
- "input, output"
