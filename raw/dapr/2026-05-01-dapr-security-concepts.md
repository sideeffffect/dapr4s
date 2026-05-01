# Dapr Security Concepts

> Source: https://docs.dapr.io/concepts/security-concept/
> Collected: 2026-05-01
> Published: Unknown

## Core Security Architecture

DAPR implements a comprehensive security model centered on application identity and encrypted communication. The framework treats security as foundational, protecting data in transit, at rest, and enforcing granular access policies.

## Application Identity and Namespace Scoping

Every DAPR-enabled application receives a unique App ID — the single atomic unit of identity in Dapr. This identifier drives routing, service discovery, and access control decisions. Multiple replicas share the same App ID, enabling consistent security policies across instances.

Namespaces provide additional isolation layers. Applications with identical App IDs in different namespaces operate independently, with security and routing remaining namespace-aware.

## Mutual TLS (mTLS) for Data Encryption

DAPR enforces on-by-default mTLS between sidecars, requiring no additional configuration. The system leverages a Sentry service functioning as a Certificate Authority to sign workload certificates.

**Key mTLS characteristics:**
- Two-way authentication between client and server
- Encrypted channels for all in-flight communication
- Workload certificates valid for 24 hours with 15-minute clock skew tolerance
- Root certificates (self-signed by default) valid for one year
- Zero-downtime certificate rotation managed by DAPR

### Certificate Management

For self-hosted deployments, Sentry generates and persists certificates to the filesystem. In Kubernetes, certificates are stored in namespace-scoped secrets accessible only to the control plane. When operators replace root certificates, Sentry automatically rebuilds trust chains without service interruption.

### IP Address Restrictions

DAPR restricts listening to `localhost` by default, preventing sidecars from accepting calls on arbitrary IP addresses. Administrators can override this with the `dapr-listen-addresses` setting.

## Access Control Mechanisms

### Service Invocation Access Policies

Services can restrict which applications call specific endpoints. The framework uses App IDs rather than IP addresses, enabling stable, portable address schemes across environments.

### Pub/Sub Topic Scoping

Topic-level security policies limit which applications can publish and subscribe to specific topics.

### API Allow Lists

Administrators can selectively enable only necessary DAPR APIs on the sidecar, reducing attack surface in zero-trust networks.

### Secret Scope Policies

Applications can be restricted to specific secrets through configuration policies. Components themselves can limit which applications access them.

## Secure Communication Patterns

### Dapr-to-Application Communication

Despite running on `localhost`, DAPR implements API-level token authentication guaranteeing:
- Only authenticated applications call into DAPR
- Applications verify DAPR callbacks using tokens

### Dapr-to-Control Plane Communication

Mandatory mTLS secures communication between sidecars and system services:
- Sentry (Certificate Authority)
- Placement (actor placement)
- Kubernetes Operator

## State Security

### Client-Side Encryption

DAPR provides automatic, transparent state encryption using AES256 at the application level.

### Stateless Runtime

The DAPR runtime maintains no persistent state, eliminating storage dependencies and allowing true stateless deployment patterns.

## Security Audit History

DAPR completed multiple independent security audits:

- **September 2023** (Ada Logics): Comprehensive audit identifying 7 non-critical issues
- **June 2023** (Ada Logics): Fuzzing audit achieving OSS-Fuzz integration with 39 new fuzzers
- **February 2021** (Cure53): Targeted 1.0 release evaluation; one high issue detected and resolved
- **June 2020** (Cure53): Foundational audit covering runtime, components, CLI, and penetration testing

As of the February 2021 audit, DAPR maintained zero critical or high-severity vulnerabilities.
