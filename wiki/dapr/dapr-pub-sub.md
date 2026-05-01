# Dapr Publish/Subscribe

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-pubsub-overview](../../raw/dapr/2026-05-01-dapr-pubsub-overview.md)
> Updated: 2026-05-01

## Overview

Dapr's pub/sub building block provides asynchronous event-driven messaging through pluggable message brokers. Publishers send messages to named topics without knowing who receives them; subscribers declare interest in topics and handle messages. Dapr wraps messages in CloudEvents 1.0 envelopes automatically and provides at-least-once delivery semantics.

## Architecture

```
Publisher App → Dapr sidecar → Message Broker (Kafka/RabbitMQ/Azure SB/Redis/...)
                                      ↓
Subscriber App ← Dapr sidecar ← Message Broker
```

The broker is a pluggable component — swap it by changing the YAML without touching application code.

## Message Envelope

All messages are wrapped in **CloudEvents 1.0** format, which provides:
- Routing metadata
- Message context (source, type, time)
- Content-type information
- Unique event ID

## Delivery Guarantee

**At-least-once delivery**: if the subscriber returns anything other than `200 OK`, Dapr retries delivery. Messages persist even through application crashes. This means subscriber handlers should be idempotent.

## Subscription Types

| Type | Where Defined | When Used |
|---|---|---|
| Declarative | External YAML files | Static subscriptions known at deploy time |
| Programmatic | Application code (GET `/dapr/subscribe`) | Static but code-managed |
| Streaming | Runtime in code | Dynamic, runtime-created subscriptions |

### Declarative Example
```yaml
apiVersion: dapr.io/v2alpha1
kind: Subscription
metadata:
  name: order-sub
spec:
  topic: orders
  routes:
    default: /checkout
  pubsubname: order-pub-sub
```

## Publishing

HTTP API:
```bash
POST http://localhost:3500/v1.0/publish/<pubsub-name>/<topic>
Content-Type: application/json
{"orderId": "100"}
```

Java SDK:
```java
client.publishEvent("order-pub-sub", "orders", orderObject).block();

// Bulk publish
client.publishEvents("order-pub-sub", "orders", "application/json",
    List.of(order1, order2, order3)).block();
```

## Subscribing

The application exposes an HTTP endpoint (or gRPC handler) at the route defined in the subscription. Dapr calls it when a message arrives:

```
POST /checkout
{"orderId": "100", "specversion": "1.0", "type": "...", ...}
```

Return `200 OK` to acknowledge. Returning any other status code triggers redelivery.

## Consumer Groups

All instances of an application sharing the same App ID automatically form a **consumer group**. Dapr routes each message to exactly one instance — the competing consumers pattern is built-in with no extra configuration.

## Advanced Features

**Content-based routing**: Route messages to different handlers based on message content using routing rules in the subscription definition.

**Dead letter topics**: Messages that repeatedly fail delivery are forwarded to a configured dead-letter topic for inspection.

**Message TTL**: Set expiry on individual messages; undelivered messages are discarded after the TTL.

**Bulk operations**: Publish and receive multiple messages in one API call for high-throughput scenarios.

**Namespace isolation**: Scope pub/sub components and topics to specific namespaces for multi-tenancy.

**Topic scoping**: Restrict which App IDs can publish or subscribe to which topics via security policies.

**StatefulSet support**: Kubernetes StatefulSet deployments get stable subscription semantics.

## Component Setup Example

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: order-pub-sub
spec:
  type: pubsub.rabbitmq
  version: v1
  metadata:
  - name: host
    value: "amqp://localhost:5672"
```

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Java SDK](dapr-java-sdk.md)
