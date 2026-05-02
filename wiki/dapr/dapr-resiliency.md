# Dapr Resiliency

> Sources: Dapr documentation, Unknown; Dapr documentation, Unknown
> Raw: [dapr-resiliency-overview](../../raw/dapr/2026-05-01-dapr-resiliency-overview.md); [dapr-resiliency-policies](../../raw/dapr/2026-05-01-dapr-resiliency-policies.md)
> Updated: 2026-05-01

## Overview

Dapr's resiliency system lets operators define named fault-tolerance policies — timeouts, retries, and circuit breakers — in a single `Resiliency` spec, then bind those policies to specific apps, actors, or components. Because the policies live in the spec rather than in application code, they can be adjusted without redeploying services. Policies are automatically applied by the Dapr sidecar, which intercepts calls to the target and enforces the defined behavior on behalf of the application.

## Resiliency Spec Structure

A resiliency spec is a Kubernetes-style YAML resource:

```yaml
apiVersion: dapr.io/v1alpha1
kind: Resiliency
metadata:
  name: myresiliency
scopes:
  - app1        # only these app IDs load this spec
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

**Deployment:**
- *Self-hosted*: file must be named `resiliency.yaml` in the components directory.
- *Kubernetes*: named specs are auto-discovered and applied to matching applications via the `scopes` list.

## Timeout Policies

Timeouts are defined as named durations and then referenced by name in `targets`:

```yaml
timeouts:
  general: 5s
  important: 60s
  largeResponse: 10s
```

## Retry Policies

Retry policies specify the backoff strategy and how many attempts to make.

| Field | Description |
|---|---|
| `policy` | `constant` (fixed interval) or `exponential` (growing interval) |
| `duration` | Wait between attempts (e.g., `5s`, `1m`) |
| `maxRetries` | Maximum attempts; `-1` means infinite |
| `maxInterval` | Ceiling for exponential backoff growth (e.g., `15s`) |

```yaml
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
```

With `exponential`, each successive wait doubles until it hits `maxInterval`. With `constant`, every wait is exactly `duration`.

## Circuit Breaker Policies

Circuit breakers protect downstream services by temporarily blocking calls after a failure threshold is reached, giving the target time to recover.

| Field | Description | Default |
|---|---|---|
| `maxRequests` | Requests allowed through while half-open | 1 |
| `interval` | Window used to reset internal counters | 0s |
| `timeout` | How long the breaker stays open before moving to half-open | 60s |
| `trip` | CEL expression that opens the breaker | `consecutiveFailures > 5` |

### Trip Expression Variables

| Variable | Meaning |
|---|---|
| `consecutiveFailures` | Unbroken run of failures |
| `requests` | Total call count since last interval reset |
| `totalFailures` | Non-consecutive cumulative failures |

### State Machine

```
CLOSED → (trip condition met) → OPEN
OPEN   → (timeout elapses)    → HALF-OPEN
HALF-OPEN → (maxRequests succeed) → CLOSED
HALF-OPEN → (any failure)         → OPEN
```

```yaml
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
```

## Applying Policies to Targets

### Apps (service-to-service invocation)

```yaml
targets:
  apps:
    appB:
      timeout: general
      retry: important
      circuitBreaker: simpleCB
```

Each policy field is optional; omit any you don't need.

### Actors

```yaml
targets:
  actors:
    myActorType:
      timeout: general
      retry: important
      circuitBreaker: simpleCB
      circuitBreakerScope: both        # "id" | "type" | "both"
      circuitBreakerCacheSize: 5000    # CBs cached per actor type
```

`circuitBreakerScope` controls granularity:
- `id` — one circuit breaker instance per actor ID (most isolated)
- `type` — one shared circuit breaker across all instances of an actor type
- `both` — track both simultaneously

### Components (inbound and outbound)

```yaml
targets:
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

`inbound` policies apply to messages delivered *to* the application (e.g., pub/sub subscriptions); `outbound` policies apply to calls the application makes *to* the component.

## Known Limitation

Service invocation via **gRPC** currently does not support resiliency policies. HTTP-based service invocation is fully supported.

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr State Management](dapr-state-management.md)
- [Dapr Pub/Sub](dapr-pub-sub.md)
- [Dapr Actors](dapr-actors.md)
- [Dapr Workflow Patterns](dapr-workflow-patterns.md)
- [Dapr Java SDK](dapr-java-sdk.md)
