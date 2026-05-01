# Dapr Sidecar (daprd)

> Source: https://docs.dapr.io/concepts/dapr-services/sidecar/
> Collected: 2026-05-01
> Published: Unknown

## Core Concept

The Dapr sidecar, called `daprd`, operates as a separate process alongside applications following the sidecar pattern. The Dapr APIs are run and exposed on a separate process, the Dapr sidecar, running alongside your application.

## Exposed APIs

The sidecar provides three categories of interfaces:

1. **Building Block APIs** - Enable application business logic functionality
2. **Metadata API** - Offers capability discovery and attribute configuration
3. **Health API** - Monitors sidecar readiness and liveness status

An important operational consideration: the sidecar reaches readiness only after the application becomes accessible on its configured port, meaning components cannot be accessed during application initialization.

## Deployment Methods

### Self-Hosted Mode
The CLI `run` command launches `daprd` with application executables, placing the binary in user home directories. This represents the recommended approach for local development and testing scenarios.

### Kubernetes Deployment
The dapr-sidecar-injector service monitors pods with the `dapr.io/enabled` annotation and injects `daprd` containers. Modern Kubernetes versions (1.28+) support native sidecars as init containers with persistent restart policies.

## Direct Sidecar Execution

Advanced users can launch `daprd` manually for debugging or scripted deployments. Essential configuration includes the `--app-id` parameter (which cannot contain dots) and optional `--app-port` specification for application endpoints.
