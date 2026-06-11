# Dapr Java SDK

> Sources: Dapr Java SDK GitHub, 2026-05-01
> Raw: [dapr-java-sdk-readme](../../raw/dapr/2026-05-01-dapr-java-sdk-readme.md); [dapr-java-sdk-actors-workflows](../../raw/dapr/2026-05-01-dapr-java-sdk-actors-workflows.md)
> Updated: 2026-05-01

## Overview

The Dapr Java SDK (current version: 1.18.0) provides a reactive, Project Reactor-based client for interacting with the Dapr sidecar. It covers all major building blocks: state management, pub/sub, bindings, secrets, configuration, actors, and workflows. The SDK communicates with the sidecar via gRPC internally (with HTTP fallback) and returns `Mono<T>` / `Flux<T>` types for all async operations.

## Installation

Recommended: use the BOM for consistent versions.

**Maven (core):**
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.dapr</groupId>
      <artifactId>dapr-sdk-bom</artifactId>
      <version>1.18.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>io.dapr</groupId>
    <artifactId>dapr-sdk</artifactId>
  </dependency>
</dependencies>
```

**Spring Boot:**
```xml
<dependency>
  <groupId>io.dapr.spring</groupId>
  <artifactId>dapr-spring-bom</artifactId>
  <version>1.18.0</version>
</dependency>
```

## Module Structure

| Maven Artifact | Root Package | Purpose |
|---|---|---|
| `dapr-sdk` | `io.dapr.client` | Core DaprClient, state, pub/sub, bindings, secrets, config, jobs |
| `sdk-actors` | `io.dapr.actors` | Actor client, proxy builder, actor annotations, ActorRuntime |
| `sdk-workflows` | `io.dapr.workflows` | Workflow base class, WorkflowContext, activities, DaprWorkflowClient |
| `testcontainers-dapr` | `io.dapr.testcontainers` | Integration testing with Testcontainers |

## DaprClientBuilder

Create a `DaprClient` via the builder:

```java
DaprClient client = new DaprClientBuilder()
    .withObjectSerializer(new JacksonSerializer())    // for requests/responses
    .withStateSerializer(new JacksonSerializer())     // for state persistence
    .withResiliencyOptions(resiliencyOptions)
    .build();
```

The default `DefaultObjectSerializer` is **not recommended for production** — implement `DaprObjectSerializer` or use a Jackson-based serializer.

Use `buildPreviewClient()` to get a `DaprPreviewClient` with access to alpha/preview APIs.

## DaprClient Key Methods

All methods return `Mono<T>` (use `.block()` for synchronous execution in non-reactive code).

### State Management
```java
// Save
client.saveState("statestore", "key", value).block();

// Read
State<MyType> s = client.getState("statestore", "key", MyType.class).block();

// Save with ETag (OCC)
client.saveState("statestore", "key", s.getEtag(), newValue, options).block();

// Bulk read
List<State<MyType>> states = client.getBulkState("statestore",
    List.of("key1", "key2"), MyType.class).block();

// Transaction
client.executeStateTransaction("statestore", ops).block();

// Delete
client.deleteState("statestore", "key").block();
```

### Pub/Sub
```java
// Publish
client.publishEvent("pubsub-name", "topic", eventObject).block();

// Bulk publish
client.publishEvents("pubsub-name", "topic", "application/json",
    List.of(e1, e2, e3)).block();
```

Subscribers implement HTTP endpoints that Dapr calls; return `200 OK` to acknowledge.

### Bindings
```java
client.invokeBinding("binding-name", "create", payload).block();

// With response
MyResponse response = client.invokeBinding("binding-name", "exec",
    request, MyResponse.class).block();
```

### Secrets
```java
Map<String, String> secret = client.getSecret("secretstore", "my-secret").block();
Map<String, Map<String, String>> all = client.getBulkSecret("secretstore").block();
```

### Configuration
```java
// Get items
List<ConfigurationItem> items = client.getConfiguration("configstore",
    List.of("key1", "key2")).block();

// Subscribe to changes (returns Flux stream)
Flux<SubscribeConfigurationResponse> stream =
    client.subscribeConfiguration("configstore", List.of("key1"));
stream.subscribe(resp -> handleConfigChange(resp));
```

### Jobs (Alpha)
```java
client.scheduleJob(new ScheduleJobRequest("backup-job", schedule)).block();
client.getJob("backup-job").block();
client.deleteJob("backup-job").block();
```

### Utility
```java
client.waitForSidecar(5000).block(); // Wait up to 5s for sidecar readiness
client.getMetadata().block();        // Dapr runtime metadata
client.shutdown().block();           // Graceful shutdown
```

### Service Invocation (Deprecated)

`invokeMethod()` exists but is **deprecated**. Use native HTTP or gRPC clients directly with the `dapr-app-id` header.

## Actors (`sdk-actors`)

### Client-Side

```java
ActorClient actorClient = new ActorClient();

// Build a proxy for a specific actor instance
ActorProxyBuilder<MyActorInterface> builder =
    new ActorProxyBuilder<>(MyActorInterface.class, actorClient);
MyActorInterface actor = builder.build(new ActorId("my-actor-id"));

// Invoke actor methods (reactive)
actor.myMethod(input).block();
```

`ActorClient` manages the gRPC channel to the Dapr sidecar — **reuse it**, don't recreate per call. It implements `AutoCloseable`.

### Server-Side (Actor Implementation)

1. Define interface extending `Actor`:
   ```java
   public interface MyActorInterface extends Actor {
       Mono<String> doWork(String input);
   }
   ```

2. Implement extending `AbstractActor`:
   ```java
   public class MyActor extends AbstractActor implements MyActorInterface {
       @Override
       public Mono<String> doWork(String input) {
           return Mono.fromCallable(() -> {
               getActorStateManager().set("lastInput", input).block();
               return "processed: " + input;
           });
       }
   }
   ```

3. Register with the ActorRuntime at application startup.

### Key Annotations
- `@ActorType(name = "MyActor")` — maps implementation to actor type name
- `@ActorMethod(name = "doWork")` — maps method to actor method name (useful when names diverge)

## Workflows (`sdk-workflows`)

See [Dapr Workflows](dapr-workflows.md) for full detail. SDK structure:

- `Workflow` interface — implement `create()` returning a `WorkflowStub`
- `WorkflowContext` — schedule activities, timers, child workflows, wait for events
- `WorkflowActivity` + `WorkflowActivityContext` — implement activities
- `DaprWorkflowClient` — start, query, terminate workflow instances
- `WorkflowTaskRetryPolicy` / `WorkflowTaskRetryHandler` — configure retry behavior

## Exception Handling

All Dapr exceptions extend `DaprException extends RuntimeException`. They are compatible with Project Reactor's error handling (`.onErrorResume()`, `.onErrorMap()`, etc.).

## Testing and Debugging

**Local debugging:**
- Set `DAPR_HTTP_PORT=3500` and `DAPR_GRPC_PORT=5001` environment variables
- Run the Dapr sidecar separately: `dapr run --app-id myapp --dapr-grpc-port 5001`
- Connect your IDE debugger to the application

**Integration tests:**
- Docker required for integration test suite
- Start test dependencies: `docker compose -f ./sdk-tests/deploy/local-test.yml up -d`
- Run: `./mvnw clean install`

**Testcontainers** (see [Dapr Testcontainers](dapr-testcontainers.md)) for in-process integration tests.

## See Also

- [Dapr Java SDK — Virtual Threads](dapr-java-sdk-virtual-threads.md)
- [Dapr JS SDK](dapr-js-sdk.md) — the Node.js counterpart (Promise-based; missing jobs & conversation)
- [Dapr Overview](dapr-overview.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Workflows](dapr-workflows.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Pub/Sub](dapr-pub-sub.md)
- [Dapr Testcontainers](dapr-testcontainers.md)
