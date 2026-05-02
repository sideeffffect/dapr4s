# Dapr Resiliency Policies

> Source: https://docs.dapr.io/operations/resiliency/policies/
> Collected: 2026-05-01
> Published: Unknown

## Policy Categories

Resiliency policies are configured in three main categories:

1. **Timeout Policies** — Configure resiliency policies for timeouts
2. **Retry Policies** — Configure resiliency policies for retries and back-offs
3. **Circuit Breaker Policies** — Configure resiliency policies for circuit breakers

Policies are defined with names in a `policies` section and then referenced from a `targets` section in the resiliency specification. This two-part structure allows operators to create named policy definitions and then selectively apply them to specific components or routes.

---

## Retry Policies

Retry policies define the backoff strategy and maximum retry count.

### Fields

| Field | Description |
|---|---|
| `policy` | Retry strategy type: `constant` or `exponential` |
| `duration` | Wait time between retry attempts (e.g., `5s`, `1m`) |
| `maxRetries` | Maximum number of retry attempts. `-1` for infinite. |
| `maxInterval` | Maximum interval between retries for exponential backoff (e.g., `15s`) |

### Backoff Types

- **constant**: Fixed wait duration between every retry attempt.
- **exponential**: Exponentially increasing wait duration between attempts, bounded by `maxInterval`.

### Examples

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

  someOperation:
    policy: exponential
    maxInterval: 15s

  largeResponse:
    policy: constant
    duration: 5s
    maxRetries: 3
```

---

## Circuit Breaker Policies

Circuit breakers automatically stop sending requests to a failing target for a period, allowing it to recover.

### Fields

| Field | Purpose | Default |
|---|---|---|
| `maxRequests` | Maximum requests allowed through when half-open | 1 |
| `interval` | Cyclical period used to clear internal counts | 0s |
| `timeout` | Duration of the open state before switching to half-open | 60s |
| `trip` | CEL expression that triggers the breaker | `consecutiveFailures > 5` |

### Trip Expression Variables

The `trip` parameter accepts Common Expression Language (CEL) statements using:

- `consecutiveFailures` — Number of sequential failed requests before opening
- `requests` — Total calls (successful or failed) before opening
- `totalFailures` — Non-consecutive total failed attempts before opening

### State Transitions

1. **Closed**: Normal operation, all traffic passes through
2. **Open**: Trip criteria met, traffic is blocked, target recovers
3. **Half-open**: Testing phase with limited traffic (`maxRequests` threshold)

### Examples

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

---

## Timeout Policies

Timeouts are defined as named durations:

```yaml
timeouts:
  general: 5s
  important: 60s
  largeResponse: 10s
```

They are referenced by name in the `targets` section.

---

## Applying Policies to Targets

### Apps

```yaml
targets:
  apps:
    appB:
      timeout: general
      retry: important
      circuitBreaker: simpleCB
```

### Actors

```yaml
targets:
  actors:
    myActorType:
      timeout: general
      retry: important
      circuitBreaker: simpleCB
      circuitBreakerScope: both       # "id", "type", or "both"
      circuitBreakerCacheSize: 5000   # number of CBs to cache per actor type
```

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
