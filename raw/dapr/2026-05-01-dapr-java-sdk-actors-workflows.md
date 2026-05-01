# Dapr Java SDK — Actors and Workflows

> Source: https://github.com/dapr/java-sdk/tree/master/sdk-actors/src/main/java/io/dapr/actors ; https://github.com/dapr/java-sdk/tree/master/sdk-workflows/src/main/java/io/dapr/workflows ; https://github.com/dapr/java-sdk/blob/master/sdk-actors/src/main/java/io/dapr/actors/client/ActorClient.java ; https://github.com/dapr/java-sdk/blob/master/sdk-workflows/src/main/java/io/dapr/workflows/Workflow.java
> Collected: 2026-05-01
> Published: Unknown

## Actors Package (`io.dapr.actors`)

### Package Structure

#### Core Files
- **ActorId.java** — Defines the actor identifier class for uniquely identifying actor instances
- **ActorMethod.java** — Annotation or interface for marking actor methods
- **ActorTrace.java** — Provides tracing capabilities for actor operations
- **ActorType.java** — Annotation or class for specifying actor type metadata
- **ActorUtils.java** — Utility functions and helper methods for actor operations

#### Subdirectories
- **client/** — Client-side actor functionality (ActorClient, ActorProxyBuilder)
- **runtime/** — Actor runtime implementation

### ActorClient Class

```java
public class ActorClient implements AutoCloseable
```

The ActorClient serves as a reusable client for communication with the Dapr sidecar. ActorClient should be reused.

**Primary Field:**
- `ManagedChannel grpcManagedChannel` — Manages the gRPC connection to the Dapr sidecar

**Core Method:**
```java
Mono<byte[]> invoke(String actorType, String actorId, 
                    String methodName, byte[] jsonPayload)
```

Returns an asynchronous result containing the actor's response.

Multiple constructor overloads support:
- Basic instantiation with no parameters
- Configuration via `Properties` and `ResiliencyOptions`
- Custom metadata and API token support

Implements `AutoCloseable` — properly close when done.

---

## Workflows Package (`io.dapr.workflows`)

### Package Structure

#### Core Files
- **Workflow.java** — Base interface defining workflow logic and structure
- **WorkflowActivity.java** — Represents individual activities within workflows
- **WorkflowActivityContext.java** — Provides context for activity execution
- **WorkflowContext.java** — Supplies context information to workflow instances
- **WorkflowStub.java** — Enables interaction with workflow instances
- **WorkflowTaskOptions.java** — Configuration options for workflow tasks
- **WorkflowTaskRetryContext.java** — Context for task retry operations
- **WorkflowTaskRetryHandler.java** — Manages retry logic for failed tasks
- **WorkflowTaskRetryPolicy.java** — Defines retry policies and strategies

#### Subdirectories
- **client/** — DaprWorkflowClient and related client interfaces
- **internal/** — Internal utilities and helper classes
- **runtime/** — Workflow runtime execution and management

### Workflow Interface

```java
public interface Workflow
```

**Key Methods:**
- `create()` — Returns a `WorkflowStub`; executes the workflow logic
- `run(WorkflowContext ctx)` — Default method providing access to methods for scheduling durable tasks and getting information about the current workflow instance

The default `run()` implementation calls `create()` and then invokes `run()` on the resulting stub.
