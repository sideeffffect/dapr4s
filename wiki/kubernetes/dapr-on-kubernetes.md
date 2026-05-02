# Dapr on Kubernetes

> Sources: Dapr docs, 2026; Direct research (Dapr CLI source pkg/kubernetes/kubernetes.go), 2026
> Raw: [Dapr on Kubernetes Setup Guide](../../raw/kubernetes/2026-05-02-dapr-on-kubernetes-setup.md); [k3d v5.x and Dapr Dev Redis Hostname](../../raw/kubernetes/2026-05-02-k3d-v5x-dapr-dev-redis-hostname.md)

## Overview

Running Dapr on Kubernetes installs a five-component control plane into the `dapr-system` namespace. Applications opt in via pod annotations; the sidecar injector automatically adds a `daprd` container to each annotated pod. Components (state stores, pub/sub brokers, bindings) are Kubernetes CRDs. The entire setup works identically on cloud clusters and local k3d/kind clusters.

## Control Plane Components

| Component               | Purpose                                                |
|-------------------------|--------------------------------------------------------|
| `dapr-operator`         | Manages component CRD updates and endpoint resolution  |
| `dapr-sidecar-injector` | Mutating webhook — injects `daprd` into annotated pods |
| `dapr-placement`        | Actor-to-pod mapping for the virtual actors building block |
| `dapr-sentry`           | mTLS CA — issues and rotates certificates              |
| `dapr-scheduler`        | Distributed job/workflow scheduling                    |

## Installation

### CLI (recommended for local dev)

```bash
dapr init -k              # minimal install
dapr init -k --dev        # + Redis state store + Redis pub/sub + Zipkin tracing
dapr init -k --wait       # block until all pods are Running
```

### Helm (recommended for CI/production)

```bash
helm repo add dapr https://dapr.github.io/helm-charts/
helm upgrade --install dapr dapr/dapr \
  --version=1.17 \
  --namespace dapr-system \
  --create-namespace \
  --wait
```

Verify: `kubectl get pods -n dapr-system` — all five pods should be `Running`.

## Sidecar Injection

Minimal pod annotation set:

```yaml
annotations:
  dapr.io/enabled: "true"
  dapr.io/app-id: "order-service"
  dapr.io/app-port: "8080"
```

The injector sets `DAPR_HTTP_PORT=3500` and `DAPR_GRPC_PORT=50001` automatically. The application talks to its sidecar on `localhost:3500` (HTTP) or `localhost:50001` (gRPC).

## Component CRDs

State store example (Redis, with `dapr init -k --dev` hostname):

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: statestore
  namespace: default
spec:
  type: state.redis
  version: v1
  metadata:
    - name: redisHost
      value: dapr-dev-redis-master.default.svc.cluster.local:6379
    - name: redisPassword
      value: ""
```

Pub/sub example (Redis Streams):

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: pubsub
  namespace: default
spec:
  type: pubsub.redis
  version: v1
  metadata:
    - name: redisHost
      value: dapr-dev-redis-master.default.svc.cluster.local:6379
```

Distributed lock example (reuses the same Redis via `lock.redis`):

```yaml
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: lockstore
  namespace: default
spec:
  type: lock.redis
  version: v1
  metadata:
    - name: redisHost
      value: dapr-dev-redis-master.default.svc.cluster.local:6379
    - name: redisPassword
      value: ""
```

**Important**: the Redis service name installed by `dapr init -k --dev` is `dapr-dev-redis-master` (Helm release `dapr-dev-redis`, Bitnami chart `<release>-master` naming). Using `redis-master` as the hostname will result in DNS lookup failures.

## Subscription CRDs

```yaml
apiVersion: dapr.io/v2alpha1
kind: Subscription
metadata:
  name: order-events-sub
spec:
  pubsubname: pubsub
  topic: orders
  routes:
    default: /orders
  scopes:
    - order-processor
```

## Complete k3d + Dapr Setup (for integration tests)

```bash
# 1. Create cluster
k3d cluster create dapr-test --agents 1

# 2. Install Dapr with dev dependencies
dapr init -k --dev --wait

# 3. Build and import application images
docker build -t order-service:dev .
k3d image import order-service:dev -c dapr-test

# 4. Apply component and application manifests
kubectl apply -f k8s/components/
kubectl apply -f k8s/deployments/

# 5. Verify
kubectl get pods
kubectl get components

# 6. Cleanup
k3d cluster delete dapr-test
```

## Version Compatibility

Dapr follows the Kubernetes version skew policy — supports the two most recent minor k8s versions. Dapr 1.17 (2026) supports k8s 1.27–1.30+. k3d ships k3s which tracks current stable k8s.

## See Also

- [Local Kubernetes Stacks](local-kubernetes-stacks.md)
- [Dapr Overview](../dapr/dapr-overview.md)
- [Dapr Building Blocks](../dapr/dapr-building-blocks.md)
