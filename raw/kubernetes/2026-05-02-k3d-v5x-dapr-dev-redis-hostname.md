# k3d v5.x Changes and Dapr Dev-Mode Redis Hostname

> Source: Direct research — Dapr CLI source (pkg/kubernetes/kubernetes.go), k3d GitHub releases and CHANGELOG
> Collected: 2026-05-02
> Published: Unknown

## k3d v5.x changes relevant to Dapr testing

Latest stable: v5.8.3 (released 2025-02-15). v5.9.0-rc.0 exists but not promoted to stable.

### Breaking changes in v5.0.0
- Nodefilter syntax changed from `[0]` to `@identifier[:index][:opt]`
- Port-mappings now route through the load balancer by default (previously direct to node)
- Old `--port` syntax in cluster creation scripts must be updated

### Notable v5.x additions
- v5.2.0: `k3d image import` gained `--mode [auto|direct|tools]` (default `auto`) — relevant when loading local Docker images for Dapr app testing
- v5.6.0: Switched network handling library (netaddr.af → netipx + netip); affects `host.k3d.internal` DNS resolution
- v5.7.0: Fixed CoreDNS config preservation across cluster restarts
- v5.8.0 was a broken release; use v5.8.1+ or v5.8.3
- v5.8.3 introduced context `--reuse` flag for `k3d image import`

### ARM64 and Windows
All 5.x releases support ARM64 (Apple Silicon, AWS Graviton) and run inside Docker Desktop on macOS and Windows WSL2.

## dapr init -k --dev Redis hostname

The `dapr init --kubernetes --dev` command installs a Redis instance using Helm with release name `dapr-dev-redis` into the `default` namespace, using the Bitnami Redis chart. The Bitnami chart names the master service `<release>-master`, so:

- **Short hostname (within default namespace):** `dapr-dev-redis-master:6379`
- **Full FQDN:** `dapr-dev-redis-master.default.svc.cluster.local:6379`
- **Kubernetes Secret name (for password):** `dapr-dev-redis`

This is confirmed in the Dapr CLI source at `pkg/kubernetes/kubernetes.go` — the generated component YAMLs use `dapr-dev-redis-master:6379`.

A common mistake is using `redis-master` (without the `dapr-dev-` prefix) in component YAMLs, which causes DNS lookup failures when connecting to the state store or pub/sub broker.
