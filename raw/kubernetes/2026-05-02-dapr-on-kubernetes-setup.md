# Dapr on Kubernetes — Setup and Deployment Guide

> Source: docs.dapr.io/operations/hosting/kubernetes/kubernetes-overview/; docs.dapr.io/operations/hosting/kubernetes/kubernetes-deploy/
> Collected: 2026-05-02
> Published: Unknown (official docs, current)

## Dapr Control Plane Components

Dapr deploys five Kubernetes services into the `dapr-system` namespace:

- **dapr-operator** — manages component updates and service endpoint management
- **dapr-sidecar-injector** — automatically injects Dapr containers into annotated pods
- **dapr-placement** — manages actor-to-pod mapping for the virtual actors feature
- **dapr-sentry** — mTLS certificate authority and certificate management
- **dapr-scheduler** — distributed job scheduling (for Jobs and Workflow APIs)

## Installation via CLI

```bash
# Check cluster context
kubectl config get-contexts
kubectl config use-context <CONTEXT>

# Initialize Dapr on the cluster
dapr init -k

# Development mode (also installs Redis + Zipkin)
dapr init -k --dev

# Wait for full initialization
dapr init -k --wait --timeout 600

# Verify — all pods should be Running
kubectl get pods --namespace dapr-system
```

Expected pods: `dapr-operator`, `dapr-placement`, `dapr-sidecar-injector`, `dapr-sentry`

## Installation via Helm

```bash
helm repo add dapr https://dapr.github.io/helm-charts/
helm repo update

helm upgrade --install dapr dapr/dapr \
  --version=1.17 \
  --namespace dapr-system \
  --create-namespace \
  --wait
```

## Sidecar Injection

Applications opt into Dapr via pod annotations. Minimal required set:

```yaml
annotations:
  dapr.io/enabled: "true"
  dapr.io/app-id: "my-service"
  dapr.io/app-port: "8080"
```

The sidecar injector automatically sets `DAPR_HTTP_PORT` (3500) and `DAPR_GRPC_PORT` (50001) environment variables in the application container.

## Application Deployment Pattern

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-service
spec:
  replicas: 1
  selector:
    matchLabels:
      app: my-service
  template:
    metadata:
      labels:
        app: my-service
      annotations:
        dapr.io/enabled: "true"
        dapr.io/app-id: "my-service"
        dapr.io/app-port: "8080"
    spec:
      containers:
        - name: my-service
          image: my-service:latest
          ports:
            - containerPort: 8080
```

## Dapr Component Configuration

Components (state stores, pub/sub brokers, etc.) are Kubernetes CRDs:

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
      value: redis-master:6379
    - name: redisPassword
      value: ""
```

## Dapr Dashboard

```bash
dapr dashboard -k
# Opens at http://localhost:8080
```

## Uninstall

```bash
dapr uninstall -k
# or
helm uninstall dapr --namespace dapr-system
```

## k3d + Dapr Workflow

```bash
# Create local cluster
k3d cluster create my-cluster

# Install Dapr (dev mode includes Redis + Zipkin)
dapr init -k --dev --wait

# Build and import local image
docker build -t my-service:dev .
k3d image import my-service:dev -c my-cluster

# Apply manifests
kubectl apply -f k8s/

# Verify
kubectl get pods
dapr dashboard -k
```

## Version Compatibility

Dapr follows the Kubernetes version skew policy — supports current and two prior minor Kubernetes versions. Dapr 1.17 (latest as of 2026) supports k8s 1.27–1.30+.
