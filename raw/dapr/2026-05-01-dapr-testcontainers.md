# Dapr Testcontainers Java Library

> Source: https://github.com/dapr/java-sdk/tree/master/testcontainers-dapr/src/main/java/io/dapr/testcontainers ; https://github.com/dapr/java-sdk/blob/master/testcontainers-dapr/src/main/java/io/dapr/testcontainers/DaprContainer.java
> Collected: 2026-05-01
> Published: Unknown

## Overview

The `io.dapr.testcontainers` package provides a containerized Dapr runtime environment for integration testing. It manages configuration of the Dapr sidecar (`daprd`), including components, subscriptions, HTTP endpoints, and supporting services.

## Core Container Classes

### DaprContainer

`DaprContainer extends GenericContainer<DaprContainer>`

The primary container for running Dapr runtime in tests.

**Constructors:**
```java
DaprContainer(DockerImageName dockerImageName)
DaprContainer(String image)
```

**Configuration Methods (Builder Pattern):**
- `withAppName()`, `withAppPort()`, `withAppProtocol()` — Application settings
- `withComponent()` — Add Dapr component (takes `Component` objects)
- `withSubscription()` — Add pub/sub subscription
- `withHttpEndpoint()` — Add HTTP endpoint configuration
- `withConfiguration()` — Set Dapr configuration
- `withPlacementImage()`, `withSchedulerImage()` — Custom supporting services
- `withDaprLogLevel()` — Control logging verbosity

**Accessor Methods:**
- `getHttpPort()`, `getGrpcPort()` — Retrieve mapped ports
- `getHttpEndpoint()`, `getGrpcEndpoint()` — Get connection endpoints
- `getComponents()`, `getSubscriptions()` — Access configured resources

**Core Method:**
- `configure()` — Orchestrates container setup, generates daprd command, creates YAML configurations, and initializes placement/scheduler containers

### DaprPlacementContainer

Container managing Dapr's placement service (required for actor support in tests).

### DaprSchedulerContainer

Container for Dapr scheduling functionality (required for jobs and reminders in tests).

### WorkflowDashboardContainer

Container hosting the workflow dashboard UI.

## Configuration Classes

- **Configuration.java** — General configuration management
- **ConfigurationSettings.java** — Base settings configuration
- **OtelTracingConfigurationSettings.java** — OpenTelemetry tracing setup
- **TracingConfigurationSettings.java** — Generic tracing configuration
- **ZipkinTracingConfigurationSettings.java** — Zipkin-specific tracing setup

## Supporting Classes

- **Component.java** — Represents Dapr components (YAML spec as Java object)
- **DaprContainerConstants.java** — Constant values used throughout
- **DaprLogLevel.java** — Log level enumeration
- **DaprProtocol.java** — Protocol definition (HTTP/gRPC)
- **AppHttpPipeline.java** — HTTP request/response pipeline handling
- **HttpEndpoint.java** — HTTP endpoint representation
- **Subscription.java** — Pub/sub subscription configuration
- **MetadataEntry.java** — Metadata key-value pairs
- **ListEntry.java** — List collection entries

## Subdirectories

- **converter/** — Data type conversion utilities
- **wait/strategy/** — Container readiness wait strategies

## Usage Pattern

Typical test setup:
```java
@Container
static DaprContainer dapr = new DaprContainer("daprio/daprd:latest")
    .withAppName("my-app")
    .withAppPort(8080)
    .withComponent(new Component("statestore", "state.redis", "v1", metadata))
    .withSubscription(new Subscription("pubsub", "orders", "/checkout"));
```

The DaprContainer handles starting supporting containers (placement, scheduler) automatically based on configuration.
