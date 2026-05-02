# Dapr Java SDK Client Lifecycle

> Source: https://docs.dapr.io/developing-applications/sdks/java/java-client/
> Collected: 2026-05-01
> Published: Unknown

## Construction and Initialization

The DaprClient is created using the DaprClientBuilder pattern:

```java
DaprClient client = new DaprClientBuilder().build()
```

This connects to the default gRPC endpoint at `localhost:50001`. The builder pattern allows configuration through environment variables and system properties for custom endpoints.

## AutoCloseable Implementation

The DaprClient implements AutoCloseable, enabling try-with-resources usage:

```java
try (DaprClient client = (new DaprClientBuilder()).build()) {
  // client operations
}
```

This ensures proper resource cleanup when exiting the block.

## Reactive Programming Model

The SDK uses Project Reactor's Mono and Flux types for asynchronous operations:

- **Mono**: Single-value async operations
- **Flux**: Multi-value streaming operations

All client methods return reactive types rather than blocking calls directly.

## Blocking Operations

To execute synchronous operations, explicitly call `.block()`:

```java
State<MyClass> state = client.getState(storeName, key, MyClass.class).block();
BulkPublishResponse response = client.publishEvents(pubsub, topic, contentType, messages).block();
```

The `.block()` method converts reactive types into synchronous results but should be used judiciously in production environments.

## Preview Client Access

Advanced APIs use DaprPreviewClient:

```java
DaprPreviewClient client = (new DaprClientBuilder()).buildPreviewClient()
```

This provides access to preview features like configuration management and query state.

## Error Handling

Since version 1.13, enhanced error details are available through DaprException:

```java
try {
  client.publishEvent(pubsub, topic, data).block();
} catch (DaprException exception) {
  String code = exception.getErrorCode();
  String reason = exception.getStatusDetails().get(
    DaprErrorDetails.ErrorDetailType.ERROR_INFO, "reason", TypeRef.STRING);
}
```
