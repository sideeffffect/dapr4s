# Dapr Publish and Subscribe Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/pubsub/pubsub-overview/
> Collected: 2026-05-01
> Published: Unknown

## Core Concept

Dapr's pub/sub API enables microservices to communicate asynchronously through message brokers. The producer, or publisher, writes messages to an input channel and sends them to a topic, unaware which application will receive them.

## Architecture

The pub/sub system operates through three layers:

1. **Application Layer**: Services call the Dapr pub/sub API
2. **Dapr Layer**: The pub/sub building block processes requests
3. **Component Layer**: A pluggable message broker (Redis, Kafka, Azure Service Bus, etc.)

This design decouples services from specific broker implementations, enhancing portability.

## Key Features

**Message Formatting**
Messages are automatically wrapped in CloudEvents 1.0 specification envelopes, providing routing capability and message context.

**Delivery Guarantees**
Dapr provides at-least-once delivery semantics. Messages persist and retry until successfully processed, even after application crashes.

**Subscription Types**
- Declarative (external configuration files)
- Streaming (runtime-defined in code)
- Programmatic (static, code-based subscriptions)

**Advanced Patterns**
- Content-based message routing to different handlers
- Dead letter topics for failed messages
- Consumer groups supporting competing consumer patterns
- Namespace isolation for multi-tenancy
- Message time-to-live (TTL) settings
- Bulk message operations for high throughput
- StatefulSet integration for Kubernetes deployments

## Consumer Groups

Multiple instances of the same application (identical `app-id`) form a consumer group. Each message routes to only one instance, implementing the competing consumers pattern automatically.

## Security

Topic scoping restricts which applications can publish or subscribe to specific topics, preventing unauthorized access.

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

## Subscription Example

Declarative subscription YAML:
```yaml
apiVersion: dapr.io/v2alpha1
kind: Subscription
metadata:
  name: order-pub-sub
spec:
  topic: orders
  routes:
    default: /checkout
  pubsubname: order-pub-sub
```

## Publishing Messages

```bash
curl -X POST http://localhost:3601/v1.0/publish/order-pub-sub/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId": "100"}'
```

## Message Acknowledgement

Services receiving messages must return a `200 OK` response to acknowledge successful processing. If Dapr receives any other return status code than `200`, or if your app crashes, Dapr will attempt to redeliver the message following at-least-once semantics.
