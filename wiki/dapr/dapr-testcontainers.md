# Dapr Testcontainers

> Sources: Dapr Java SDK GitHub, 2026-05-01; diagridio/testcontainers-dapr source, 2026-05-01
> Raw: [dapr-testcontainers](../../raw/dapr/2026-05-01-dapr-testcontainers.md); [testcontainers-dapr javadoc](../../raw/dapr/2026-05-01-testcontainers-dapr-javadoc.md)
> Updated: 2026-05-01

## Overview

The `testcontainers-dapr` module provides a Testcontainers-based integration testing framework for Dapr Java applications. It spins up a real `daprd` sidecar (and any required control plane services) inside Docker during test execution, allowing full end-to-end tests against actual Dapr runtime behavior without a Kubernetes cluster.

## Dependency

There are two distinct Maven artifacts depending on which repo you use:

**diagridio/testcontainers-dapr** (standalone, community-maintained):
```xml
<dependency>
    <groupId>io.diagrid.dapr</groupId>
    <artifactId>testcontainers-dapr</artifactId>
    <version>0.10.x</version>
</dependency>
```

**io.dapr (Dapr Java SDK BOM):**
```xml
<dependency>
  <groupId>io.dapr</groupId>
  <artifactId>testcontainers-dapr</artifactId>
  <!-- version managed by dapr-sdk-bom -->
</dependency>
```

The source analysis in this article is based on `io.diagrid.dapr:testcontainers-dapr` (diagridio/testcontainers-dapr). The package names differ: `io.diagrid.dapr` vs `io.dapr.testcontainers`.

## Core Classes

### DaprContainer

`DaprContainer extends GenericContainer<DaprContainer>`

The primary test container. It manages the Dapr sidecar (`daprd`) process and automatically starts/links supporting containers (placement, scheduler) as needed.

**Basic Setup (app running in Docker on same network):**
```java
@Container
static DaprContainer dapr = new DaprContainer("daprio/daprd")
    .withAppName("test-app")
    .withAppPort(8080);
```

**App running on host machine (e.g., JUnit test process):**
```java
@ClassRule
public static DaprContainer daprContainer = new DaprContainer("daprio/daprd")
    .withAppName("dapr-app")
    .withAppPort(8081)
    .withAppChannelAddress("host.testcontainers.internal");  // reach app on host

// Also required:
Testcontainers.exposeHostPorts(8081);
```

`withAppChannelAddress` sets where `daprd` looks for the application channel. Use `"host.testcontainers.internal"` when the app is the JUnit test process on the host, not a container. The container calls `withAccessToHost(true)` internally to enable this.

**With Components and Subscription (fluent API):**
```java
DaprContainer dapr = new DaprContainer("daprio/daprd")
    .withAppName("test-app")
    .withAppPort(8080)
    .withComponent(new Component("statestore", "state.in-memory", "v1",
        Collections.emptyMap()))
    .withComponent(new Component("pubsub", "pubsub.in-memory", "v1",
        Collections.emptyMap()))
    .withSubscription("my-sub", "pubsub", "orders", "/checkout")
    .withDaprLogLevel(DaprLogLevel.debug);
```

Note: `withSubscription` takes four strings: `(name, pubsubName, topic, route)`.

**Loading a component from a YAML file:**
```java
dapr.withComponent(Paths.get("src/test/resources/components/statestore.yaml"));
```

**Accessing ports in tests:**
```java
int httpPort = dapr.getHttpPort();
int grpcPort = dapr.getGrpcPort();
String httpEndpoint = dapr.getHttpEndpoint(); // "http://<host>:<port>"
```

**Build DaprClient using system property (diagridio variant):**
```java
System.setProperty("dapr.grpc.port", Integer.toString(daprContainer.getGrpcPort()));
// DaprClientBuilder then picks it up automatically
DaprClient client = new DaprClientBuilder().build();
```

**Build DaprClient with explicit override:**
```java
DaprClient client = new DaprClientBuilder()
    .withPropertyOverride(Properties.HTTP_PORT, String.valueOf(dapr.getHttpPort()))
    .withPropertyOverride(Properties.GRPC_PORT, String.valueOf(dapr.getGrpcPort()))
    .build();
```

### DaprPlacementContainer

`DaprPlacementContainer extends GenericContainer<DaprPlacementContainer>`

Required for actor-based tests. `DaprContainer` creates and starts this automatically when `configure()` runs, unless one is provided explicitly. Default image: `daprio/placement`, default port: `50006`.

```java
// Override placement image:
dapr.withPlacementImage("daprio/placement:1.14.0");

// BYO placement container (useful for multi-DaprContainer test setups):
DaprPlacementContainer placement = new DaprPlacementContainer("daprio/placement")
    .withNetwork(sharedNetwork)
    .withNetworkAliases("placement");
placement.start();
dapr.withPlacementContainer(placement);

// Reuse placement container across test classes:
dapr.withReusablePlacement(true);
```

`DaprPlacementContainer` runs `./placement -port 50006` and exposes that port.

### DaprSchedulerContainer

Required for jobs and workflow reminders. Started automatically when needed:
```java
dapr.withSchedulerImage("daprio/scheduler:latest"); // optional: override image
```

### WorkflowDashboardContainer

Optional workflow monitoring UI:
```java
WorkflowDashboardContainer dashboard = new WorkflowDashboardContainer("daprio/workflow-dashboard:latest");
```

## Configuration Classes

Use these to configure tracing within the container:

```java
dapr.withConfiguration(new Configuration(
    new OtelTracingConfigurationSettings("http://otel-collector:4317")
));
```

Available settings:
- `OtelTracingConfigurationSettings(endpoint)` — OpenTelemetry tracing
- `ZipkinTracingConfigurationSettings(endpoint)` — Zipkin tracing
- `TracingConfigurationSettings` — generic base

## Component Class

Represents a Dapr component in Java (equivalent to the YAML component definition). Two constructors:

```java
// With Map<String, Object> metadata:
new Component(
    "statestore",           // component name
    "state.redis",          // component type
    "v1",                   // version
    Map.of(                 // metadata as map
        "redisHost", "redis:6379",
        "redisPassword", ""
    )
)

// With List<MetadataEntry> metadata:
new Component(
    "statestore",
    "state.redis",
    "v1",
    List.of(
        new MetadataEntry("redisHost", "redis:6379"),
        new MetadataEntry("redisPassword", "")
    )
)
```

For metadata values that must be YAML-quoted booleans (required by some Dapr components such as `state.in-memory` for `actorStateStore`), use the `QuotedBoolean` helper:

```java
new Component("statestore", "state.in-memory", "v1",
    Collections.singletonMap("actorStateStore", new QuotedBoolean("true")))
```

This produces `value: "true"` in the generated YAML (quoted string, not unquoted boolean).

**Default components**: If no components are added before the container starts, `DaprContainer` auto-adds:
- `kvstore` (type `state.in-memory`, version `v1`)
- `pubsub` (type `pubsub.in-memory`, version `v1`)

## Subscription Class

Full constructor:

```java
new Subscription(
    "my-sub",     // subscription name
    "pubsub",     // pubsub component name
    "orders",     // topic
    "/checkout"   // route on the app
)
```

Or via the fluent method directly on `DaprContainer`:
```java
dapr.withSubscription("my-sub", "pubsub", "orders", "/checkout")
```

**Default subscription**: If no subscriptions are added, `DaprContainer` auto-adds `local` (pubsub `pubsub`, topic `topic`, route `/events`).

## Wait Strategies

The `wait/strategy/` package provides custom readiness strategies. DaprContainer uses these to ensure `daprd` is healthy before tests run.

**Important**: `DaprContainer` deliberately does NOT configure a startup wait strategy for `daprd` readiness. This is by design: `daprd` needs to call back to the application for subscription setup, creating a chicken-and-egg dependency. Tests must handle their own readiness synchronization (e.g., calling `client.waitForSidecar(5000).block()`).

## JUnit 5 Integration Pattern

```java
@Testcontainers
class DaprIntegrationTest {
    
    static Network network = Network.newNetwork();
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
        .withNetwork(network)
        .withNetworkAliases("redis")
        .withExposedPorts(6379);
    
    @Container
    static DaprContainer dapr = new DaprContainer("daprio/daprd:1.14.0")
        .withNetwork(network)
        .withAppName("my-test-app")
        .withAppPort(8080)
        .withComponent(new Component("statestore", "state.redis", "v1",
            List.of(new MetadataEntry("redisHost", "redis:6379"))));
    
    DaprClient client;
    
    @BeforeEach
    void setUp() {
        client = new DaprClientBuilder()
            .withPropertyOverride(Properties.GRPC_PORT, String.valueOf(dapr.getGrpcPort()))
            .build();
    }
    
    @Test
    void testSaveAndReadState() {
        client.saveState("statestore", "key1", "value1").block();
        State<String> state = client.getState("statestore", "key1", String.class).block();
        assertEquals("value1", state.getValue());
    }
}
```

## `configure()` Internals

Understanding what `configure()` does helps when troubleshooting:

1. Creates a new `Network` if none is set.
2. Creates and starts a `DaprPlacementContainer` if none provided, attaching it to the same network with alias `"placement"`.
3. Builds `daprd` command: `./daprd -app-id <name> --dapr-listen-addresses=0.0.0.0 --app-protocol http -placement-host-address placement:50006 --app-channel-address <host> --app-port <port> --log-level <level> -components-path /components`
4. Injects default components (kvstore, pubsub) if the component set is empty.
5. Injects default subscription (`local`) if the subscription set is empty.
6. Copies all component and subscription YAML files to `/components/` inside the container.

## Key Considerations

- `DaprContainer` generates `daprd` CLI arguments and YAML configs at startup time via `configure()`
- Containers on the same Docker network can communicate using their network aliases
- For actor tests, the placement container is created and started automatically
- `daprd` does NOT wait for full readiness before the test starts; call `client.waitForSidecar(timeout).block()` explicitly
- When the app runs on the host (not in Docker), use `withAppChannelAddress("host.testcontainers.internal")` and `Testcontainers.exposeHostPorts(port)`
- `QuotedBoolean` is required for metadata values that must appear as quoted strings in YAML (e.g., `actorStateStore: "true"`)

## See Also

- [Dapr Java SDK](dapr-java-sdk.md)
- [Dapr Overview](dapr-overview.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Workflows](dapr-workflows.md)
