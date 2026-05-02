# Local Kubernetes Stacks

> Sources: Various authors, 2025–2026; Direct research (Dapr CLI source, k3d CHANGELOG), 2026
> Raw: [Local Kubernetes Stacks Comparison](../../raw/kubernetes/2026-05-02-local-kubernetes-stacks-comparison.md); [k3d v5.x and Dapr Dev Redis Hostname](../../raw/kubernetes/2026-05-02-k3d-v5x-dapr-dev-redis-hostname.md)

## Overview

Six major tools exist for running Kubernetes locally: **k3d**, **kind**, **k3s**, **k0s**, **Minikube**, and **MicroK8s**. They differ on three axes: whether they require Docker, whether they need root, and how closely they mirror production. For Dapr integration testing in CI and local development, **k3d** is the best choice — it runs k3s (a CNCF-certified distribution) inside Docker containers, requires no root, supports multi-node clusters, and can import locally-built images without a registry.

## Tool Summaries

**k3d** wraps k3s in Docker containers. Each cluster node is a Docker container. `k3d image import` loads local images directly into the cluster, skipping the need for a registry. Traefik ingress is included. Startup is under 30 seconds. No root required. This makes it ideal for CI pipelines and local Dapr testing. Current stable version is **v5.8.3** (use v5.8.1+ — v5.8.0 was a broken release).

**kind** (Kubernetes in Docker) was built by the Kubernetes team to test Kubernetes itself. Also Docker-native, no root, very fast. Better than k3d for testing Kubernetes conformance; slightly less convenient for application development because there is no built-in ingress.

**k3s** is the CNCF-certified distribution that k3d wraps. Used directly (without Docker) it runs on bare Linux with 512 MB RAM, making it the right choice for edge, IoT, and lightweight VPS production deployments.

**k0s** is Mirantis's "zero dependencies" distribution — a single statically-linked binary. No Docker, no kernel modules, no package manager required. Linux-only and requires root. Best for bare-metal environments where Docker is not available.

**Minikube** is the oldest and most feature-rich local tool. It has the richest addon ecosystem (Istio, dashboard, registry, metrics-server). Higher resource overhead than container-based tools. Best for learning and feature exploration.

**MicroK8s** is Canonical's snap-packaged distribution. Enterprise-backed, installs on 42+ Linux distributions, requires root. Suitable for Ubuntu-centric teams and IoT/edge scenarios.

## Quick Comparison

| Tool     | Requires Docker | Root needed | Startup     | Best for                     |
|----------|-----------------|-------------|-------------|------------------------------|
| k3d      | Yes             | No          | ~20s        | Local dev, CI, Dapr testing  |
| kind     | Yes             | No          | ~30s        | k8s conformance, CI          |
| k3s      | No              | No          | ~5s (bare)  | Edge/IoT, lightweight prod   |
| k0s      | No              | Yes         | ~5s (bare)  | Zero-dep bare metal          |
| Minikube | Optional        | No          | 1–5 min     | Learning, full addon suite   |
| MicroK8s | No              | Yes         | ~10s (snap) | Ubuntu/enterprise edge       |

## Multi-Node Support

k3d, kind, and Minikube automate multi-node cluster creation via their CLIs. k0s and MicroK8s require manual node provisioning and token-based joining — not suited for automated test environments.

## ARM and Windows Support (2025–2026)

All six tools now support ARM64 (Apple Silicon, AWS Graviton). k3d and kind both run well inside Docker Desktop on macOS Apple Silicon and inside Windows WSL2.

## k3d v5.x Notable Changes

**Breaking changes in v5.0.0** (upgrade from v4.x):
- Nodefilter syntax changed from `[0]` to `@identifier[:index][:opt]`
- Port-mappings now route through the load balancer by default; old `--port` syntax must be updated

**Additions relevant to Dapr testing:**
- v5.2.0: `k3d image import --mode [auto|direct|tools]` — `auto` detects the best method for loading local images; use `direct` if image import is slow
- v5.7.0: Fixed CoreDNS config preservation across cluster restarts (important when clusters are stopped and restarted between CI jobs)
- v5.8.3: `k3d image import --reuse` context flag

## Recommendation for Dapr Integration Tests

Use **k3d v5.8.3+** with `dapr init -k --dev`:

```bash
# Create cluster
k3d cluster create dapr-test --agents 1

# Install Dapr with dev dependencies (Redis + Zipkin included)
dapr init -k --dev --wait

# Import locally-built service images
k3d image import my-service:dev -c dapr-test

# Tear down
k3d cluster delete dapr-test
```

The `--dev` flag bundles a Redis instance (`dapr-dev-redis`) and Zipkin tracing, providing all the component dependencies (state store, pub/sub broker, distributed lock) that Dapr integration tests typically need — without manually deploying infrastructure.

### Dev-mode Redis hostname

The Redis instance installed by `dapr init -k --dev` uses the Helm release name `dapr-dev-redis`. The Bitnami Redis chart names the master service `<release>-master`, so the in-cluster address is:

```
dapr-dev-redis-master.default.svc.cluster.local:6379
```

Use this exact FQDN in Dapr component CRDs (`redisHost` metadata value). Using `redis-master` without the `dapr-dev-` prefix causes DNS failures. See [Dapr on Kubernetes](dapr-on-kubernetes.md) for component YAML examples.

## See Also

- [Dapr on Kubernetes](dapr-on-kubernetes.md)
- [Dapr Overview](../dapr/dapr-overview.md)
