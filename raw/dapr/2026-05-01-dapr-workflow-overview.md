# Dapr Workflow Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/workflow/workflow-overview/
> Collected: 2026-05-01
> Published: Unknown

## Core Concept

Dapr Workflow enables developers to write reliable business logic and integrations with built-in fault tolerance. The system provides a built-in workflow runtime for driving Dapr Workflow execution with support for long-running, stateful applications ideal for microservice orchestration.

## Key Features

### Workflows and Activities

The framework organizes work into activities — basic units that call other services, interact with state stores, and connect to external systems. These components orchestrate together within defined workflows.

### Child Workflows

Developers can schedule workflows to call other workflows as child processes. Each maintains its own instance ID, history, and status that is independent of the parent workflow while remaining linked through termination behavior.

### Multi-Application Support

Workflows can span across multiple applications, enabling complex business processes that span across multiple applications while maintaining security and durability guarantees.

### Timers and Reminders

Like Dapr actors, workflows support reminder-like durable delays for any time range, enabling scheduled actions.

### Management Operations

HTTP and gRPC APIs allow starting, terminating, pausing, resuming, and querying workflow instances.

## State Management — Event Sourcing

The system uses event sourcing — maintaining an append-only history log rather than snapshots. When workflows await task results, they unload from memory. Upon completion, the workflow replays from the beginning, returning cached results for finished tasks until reaching incomplete work.

## Activities

Activities represent basic work units with minimal constraints. Activities execute "at least once" and can perform network calls, CPU-intensive operations, and return data. Implementing idempotent logic is recommended since duplicate execution may occur.

## External Events

Named signals with payloads delivered to specific instances. Workflows create wait-tasks that block until events arrive, enabling approval processes, game logic, and external system interaction.

## Critical Limitations

### Determinism Requirement

Workflow code must be strictly deterministic for replay functionality. This prohibits:
- Random number generation
- UUID creation
- Current date/time calls
- Global variables
- Environment variable access
- Filesystem operations
- Network calls within workflow logic

These must occur within activities instead or use SDK-provided deterministic equivalents.

### Code Updates

Modifications must preserve determinism for existing non-completed instances or runtime failures occur during replay.

## Supported Languages

- Python (dapr-ext-workflow)
- JavaScript (DaprWorkflowClient)
- .NET (Dapr.Workflow)
- Java (io.dapr.workflows)
- Go (workflow package)

## Practical Use Cases

Example applications include order processing, HR onboarding, digital menu rollouts, and image processing workflows involving multiple coordinated services.

## Important Notes

State store compatibility matters — only certain stores support workflows. Azure Cosmos DB and AWS DynamoDB have specific payload and complexity restrictions requiring careful consideration during implementation.

## Versioning

The system supports continue-as-new API for eternal workflows and history truncation. Completed workflows can be purged from state stores, removing all metadata and history.
