# Dapr Java SDK — README and Key Documentation

> Source: https://github.com/dapr/java-sdk
> Collected: 2026-05-01
> Published: Unknown

## Overview

The Dapr SDK for Java enables developers to build distributed applications with support for PubSub, Service Invocation, Binding, State Store, Actors, and Workflows.

## Installation

### Using Bill of Materials (BOM) — Recommended

**For core projects:**
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

**For Spring Boot projects:**
```xml
<dependency>
  <groupId>io.dapr.spring</groupId>
  <artifactId>dapr-spring-bom</artifactId>
  <version>1.18.0</version>
</dependency>
```

## Key Architecture

Applications interact with the Dapr runtime through the Java SDK, which communicates via HTTP or gRPC. The SDK connects to a Dapr sidecar that manages interactions with state stores, pub/sub systems, and other services.

## SDK Module Structure

| Package | Purpose |
|---------|---------|
| `io.dapr.client` | Core client implementation for interacting with Dapr services |
| `io.dapr.config` | Configuration management and settings |
| `io.dapr.exceptions` | Custom exception classes for error handling |
| `io.dapr.internal` | Internal utilities and implementation details |
| `io.dapr.serializer` | Data serialization and deserialization components |
| `io.dapr.utils` | Utility functions and helper classes |
| `io.dapr.actors` | Actor runtime client and annotations |
| `io.dapr.actors.client` | ActorClient and ActorProxyBuilder |
| `io.dapr.actors.runtime` | Actor runtime management |
| `io.dapr.workflows` | Workflow base classes and context |
| `io.dapr.workflows.client` | DaprWorkflowClient |
| `io.dapr.workflows.runtime` | Workflow runtime |
| `io.dapr.testcontainers` | Testcontainers support |

## Core Concepts

### Reactor API

Built on Project Reactor, the SDK provides asynchronous operations returning `Mono` publishers. Use `block()` to execute synchronously:

```java
Mono<Void> result = daprClient.publishEvent("mytopic", "message");
result.block();
```

### Custom Serialization

Implement `DaprObjectSerializer` interface for production scenarios involving request/response and state object serialization. The default `DefaultObjectSerializer` is not recommended for production scenarios.

## DaprClient Interface

The `DaprClient` interface (extends `AutoCloseable`) provides a unified interface for all Dapr capabilities through reactive types:

### Service Invocation (Deprecated)
Multiple overloaded `invokeMethod()` signatures — all marked `@Deprecated`. Guidance: use language-native HTTP clients or gRPC clients for service invocation instead.

### Pub/Sub Messaging
- `publishEvent()` — single event publishing with optional metadata
- `publishEvents()` — bulk event publishing supporting List or varargs

### State Management
- `getState()` / `getBulkState()` — retrieve state(s) with TypeRef or Class type specification
- `saveState()` / `saveBulkState()` — persist single or multiple states with optional etags and StateOptions
- `deleteState()` — remove state entries conditionally via etags
- `executeStateTransaction()` — batch transactional operations

### Binding Operations
- `invokeBinding()` — multiple overloads for binding invocation

### Secret Management
- `getSecret()` — fetch single secret with optional metadata
- `getBulkSecret()` — retrieve all secrets from configured vault

### Configuration Management
- `getConfiguration()` — retrieve single or multiple configuration items
- `subscribeConfiguration()` / `unsubscribeConfiguration()` — reactive configuration change monitoring via Flux streams

### Job Scheduling
- `scheduleJob()` — schedule jobs with request details
- `getJob()` — retrieve job information
- `deleteJob()` — remove scheduled jobs

### Utility Methods
- `waitForSidecar()` — initialization with timeout support
- `newGrpcStub()` — custom gRPC stub creation with service invocation interceptors
- `getMetadata()` — fetch Dapr runtime metadata
- `shutdown()` — graceful runtime termination

## DaprClientBuilder

```java
DaprClient client = new DaprClientBuilder()
    .withObjectSerializer(mySerializer)
    .withStateSerializer(myStateSerializer)
    .withResiliencyOptions(options)
    .build();
```

Key configuration methods:
- `withObjectSerializer()` — for request/response handling
- `withStateSerializer()` — for persistence operations
- `withResiliencyOptions()` — sets ResiliencyOptions for client resilience
- `withPropertyOverride()` / `withPropertyOverrides()` — override static properties
- `build()` — returns DaprClient
- `buildPreviewClient()` — returns DaprPreviewClient

## Available Examples

- HTTP service invocation
- gRPC service invocation
- State management
- PubSub messaging and streaming
- Input bindings
- Actor implementations
- Workflow orchestration
- Secrets and configuration management
- Distributed tracing with OpenTelemetry
- Exception handling patterns
- Unit testing strategies

## Development Setup

**Prerequisites:**
- Java (via SDKMAN! recommended)
- Maven 3.x or Gradle 6.x
- Docker and Docker Compose for integration tests

**Build:**
```bash
./mvnw clean install
```

## Exception Handling

Most exceptions extend `DaprException` from `RuntimeException`, ensuring compatibility with Project Reactor.

## Debugging

Set environment variables when debugging locally:
- `DAPR_HTTP_PORT=3500`
- `DAPR_GRPC_PORT=5001`

Run the Dapr sidecar separately, then debug from your IDE with these ports configured.
