# Dapr Resiliency Overview

> Source: https://docs.dapr.io/operations/resiliency/resiliency-overview/
> Collected: 2026-05-01
> Published: Unknown

## What is Resiliency?

Dapr enables fault tolerance through resiliency policies that automatically handle failures in microservices. Dapr provides the capability for defining and applying fault tolerance resiliency policies via a resiliency spec.

## Core Policy Types

Three main policy categories exist:

1. **Timeouts**: Named durations that limit how long operations can run
2. **Retries**: Templates defining retry behavior with constant or exponential backoff
3. **Circuit Breakers**: Automatically instantiated per component/app, maintaining counters that trip based on failure thresholds

## Policy Structure

```yaml
apiVersion: dapr.io/v1alpha1
kind: Resiliency
metadata:
  name: myresiliency
scopes:
  - app1
  - app2
spec:
  policies:
    timeouts: { }
    retries: { }
    circuitBreakers: { }
  targets:
    apps: { }
    actors: { }
    components: { }
```

## Deployment Scopes

**Self-hosted mode**: The file must be named `resiliency.yaml` in the components directory.

**Kubernetes**: Named resiliency specs are automatically discovered and applied to matching applications.

## Target Types

Policies apply to three interaction categories:

- **Apps**: Service-to-service communication via invocation
- **Components**: State stores, pub/sub brokers, and bindings
- **Actors**: Virtual actor types with configurable circuit breaker scoping

## Complete Configuration Example

```yaml
apiVersion: dapr.io/v1alpha1
kind: Resiliency
metadata:
  name: myresiliency
scopes:
  - app1
  - app2
spec:
  policies:
    timeouts:
      general: 5s
      important: 60s
      largeResponse: 10s

    retries:
      pubsubRetry:
        policy: constant
        duration: 5s
        maxRetries: 10

      retryForever:
        policy: exponential
        maxInterval: 15s
        maxRetries: -1

      important:
        policy: constant
        duration: 5s
        maxRetries: 30

      someOperation:
        policy: exponential
        maxInterval: 15s

      largeResponse:
        policy: constant
        duration: 5s
        maxRetries: 3

    circuitBreakers:
      simpleCB:
        maxRequests: 1
        timeout: 30s 
        trip: consecutiveFailures >= 5

      pubsubCB:
        maxRequests: 1
        interval: 8s
        timeout: 45s
        trip: consecutiveFailures > 8

  targets:
    apps:
      appB:
        timeout: general
        retry: important
        circuitBreaker: simpleCB

    actors:
      myActorType:
        timeout: general
        retry: important
        circuitBreaker: simpleCB
        circuitBreakerScope: both
        circuitBreakerCacheSize: 5000

    components:
      statestore1:
        outbound:
          timeout: general
          retry: retryForever
          circuitBreaker: simpleCB

      pubsub1:
        outbound:
          retry: pubsubRetry
          circuitBreaker: pubsubCB

      pubsub2:
        outbound:
          retry: pubsubRetry
          circuitBreaker: pubsubCB
        inbound:
          timeout: general
          retry: important
          circuitBreaker: pubsubCB
```

## Key Limitation

Service invocation via gRPC currently does not support resiliency policies, though HTTP-based service invocation does.
