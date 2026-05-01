# Dapr Testcontainers

> Sources: Dapr Java SDK GitHub, 2026-05-01
> Raw: [dapr-testcontainers](../../raw/dapr/2026-05-01-dapr-testcontainers.md)
> Updated: 2026-05-01

## Overview

The `testcontainers-dapr` module provides a Testcontainers-based integration testing framework for Dapr Java applications. It spins up a real `daprd` sidecar (and any required control plane services) inside Docker during test execution, allowing full end-to-end tests against actual Dapr runtime behavior without a Kubernetes cluster.

## Dependency

```xml
<dependency>
  <groupId>io.dapr</groupId>
  <artifactId>testcontainers-dapr</artifactId>
  <!-- version managed by dapr-sdk-bom -->
</dependency>
```

## Core Classes

### DaprContainer

`DaprContainer extends GenericContainer<DaprContainer>`

The primary test container. It manages the Dapr sidecar (`daprd`) process and automatically starts/links supporting containers (placement, scheduler) as needed.

**Basic Setup:**
```java
@Container
static DaprContainer dapr = new DaprContainer("daprio/daprd:latest")
    .withAppName("test-app")
    .withAppPort(8080)
    .withAppProtocol(DaprProtocol.HTTP);
```

**With Components:**
```java
@Container
static DaprContainer dapr = new DaprContainer("daprio/daprd:latest")
    .withAppName("test-app")
    .withAppPort(8080)
    .withComponent(new Component("statestore", "state.redis", "v1",
        List.of(new MetadataEntry("redisHost", "localhost:6379"))))
    .withComponent(new Component("pubsub", "pubsub.redis", "v1",
        List.of(new MetadataEntry("redisHost", "localhost:6379"))))
    .withSubscription(new Subscription("pubsub", "orders", "/checkout"))
    .withDaprLogLevel(DaprLogLevel.DEBUG);
```

**Accessing ports in tests:**
```java
int httpPort = dapr.getHttpPort();
int grpcPort = dapr.getGrpcPort();
String httpEndpoint = dapr.getHttpEndpoint(); // "http://localhost:<port>"
String grpcEndpoint = dapr.getGrpcEndpoint();
```

**Build DaprClient from container:**
```java
DaprClient client = new DaprClientBuilder()
    .withPropertyOverride(Properties.HTTP_PORT, String.valueOf(dapr.getHttpPort()))
    .withPropertyOverride(Properties.GRPC_PORT, String.valueOf(dapr.getGrpcPort()))
    .build();
```

### DaprPlacementContainer

Required for actor-based tests. DaprContainer starts this automatically when actor types are registered:
```java
dapr.withPlacementImage("daprio/placement:latest"); // optional: override image
```

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

Represents a Dapr component in Java (equivalent to the YAML component definition):

```java
new Component(
    "statestore",           // component name
    "state.redis",          // component type
    "v1",                   // version
    List.of(                // metadata entries
        new MetadataEntry("redisHost", "redis:6379"),
        new MetadataEntry("redisPassword", "")
    )
)
```

## Subscription Class

Configures pub/sub subscriptions:

```java
new Subscription("pubsub-component-name", "topic-name", "/handler-path")
```

## Wait Strategies

The `wait/strategy/` package provides custom readiness strategies. DaprContainer uses these to ensure `daprd` is healthy before tests run.

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

## Key Considerations

- DaprContainer generates `daprd` CLI arguments and YAML configs at startup time via `configure()`
- Containers on the same Docker network can communicate using their network aliases
- For actor tests, ensure the placement container is linked (handled automatically)
- The `daprd` sidecar reaches readiness only after the application under test is accessible on its configured port

## See Also

- [Dapr Java SDK](dapr-java-sdk.md)
- [Dapr Overview](dapr-overview.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Workflows](dapr-workflows.md)
