# Dapr Other Building Blocks: Bindings, Secrets, Configuration, Distributed Lock, Cryptography, Jobs

> Sources: Dapr Documentation, 2026-05-01
> Raw: [dapr-bindings-overview](../../raw/dapr/2026-05-01-dapr-bindings-overview.md); [dapr-secrets-overview](../../raw/dapr/2026-05-01-dapr-secrets-overview.md); [dapr-config-lock-crypto-jobs](../../raw/dapr/2026-05-01-dapr-config-lock-crypto-jobs.md)
> Updated: 2026-05-01

## Overview

These six building blocks complete Dapr's portfolio beyond the primary data-plane capabilities (state, pub/sub, actors, workflows, service invocation). Each solves a specific cross-cutting concern for microservices.

---

## Bindings

**API**: `/v1.0/bindings`

Bindings integrate applications with external systems without requiring application-embedded SDKs. Dapr handles connection management, retries, and credential configuration.

### Input Bindings (inbound triggers)

An input binding subscribes to an external event source and triggers the application when events arrive:
- External systems: message queues, storage events, IoT sensors, webhooks
- Application implements an HTTP endpoint (or gRPC handler) named after the binding
- Dapr calls the endpoint when events occur
- At startup, Dapr sends an `OPTIONS` request to each binding endpoint; the application responds `2xx` or `405` to confirm subscription

```yaml
# Component definition
apiVersion: dapr.io/v1alpha1
kind: Component
metadata:
  name: kafka-binding
spec:
  type: bindings.kafka
  version: v1
  metadata:
  - name: brokers
    value: "kafka:9092"
  - name: topics
    value: "my-topic"
```

Application endpoint: `POST /kafka-binding` called when a Kafka message arrives.

### Output Bindings (outbound calls)

Application calls the Dapr API to invoke external systems:
```java
client.invokeBinding("binding-name", "create", myPayload).block();
```
Common operations: `create`, `update`, `delete`, `exec`, `query`.

### Direction Metadata

Optional `direction` field in binding YAML reduces sidecar-app lifecycle dependencies:
- `"input"`, `"output"`, or `"input, output"`

---

## Secrets

**API**: `/v1.0/secrets`

Unified API for retrieving secrets from any configured store without provider-specific code.

### Supported Stores
AWS Secrets Manager, Azure Key Vault, Google Cloud Secret Manager, HashiCorp Vault, Kubernetes Secrets, local files (for dev), environment variables.

### Usage

```java
// Retrieve a specific secret
Map<String, String> secret = client.getSecret("my-secret-store", "db-password").block();
String password = secret.get("db-password");

// Retrieve all secrets
Map<String, Map<String, String>> all = client.getBulkSecret("my-secret-store").block();
```

### Component Reference

Secrets can be referenced inside other component YAML files to avoid embedding credentials:
```yaml
spec:
  metadata:
  - name: password
    secretKeyRef:
      name: db-password
      key: password
auth:
  secretStore: my-secret-store
```

### Access Control

Secret scoping restricts which App IDs can read which secrets:
```yaml
# Dapr configuration
spec:
  secrets:
    scopes:
    - storeName: my-secret-store
      defaultAccess: deny
      allowedSecrets: ["api-key", "db-password"]
```

### Kubernetes Default

In Kubernetes, Dapr automatically configures a `kubernetes` secret store referencing the cluster's native Secrets API.

---

## Configuration

**API**: `/v1.0/configuration`

Read-only, dynamic configuration key/value store with change subscriptions. Distinct from Dapr's own operational configuration.

### Key Characteristics
- Items are **read-only** through this API; updates are done externally (ops tooling)
- Supports real-time subscription to changes via Flux stream (gRPC/HTTP Server-Sent Events)
- Typical items: feature flags, service endpoint references, tuning parameters

### Usage

```java
// Read specific keys
List<ConfigurationItem> items = client.getConfiguration("configstore",
    List.of("feature-x-enabled", "max-retries")).block();

// Subscribe to changes
Flux<SubscribeConfigurationResponse> stream =
    client.subscribeConfiguration("configstore", List.of("feature-x-enabled"));
stream.subscribe(change -> {
    boolean enabled = Boolean.parseBoolean(change.getItems().get("feature-x-enabled").getValue());
    updateFeatureFlag(enabled);
});

// Unsubscribe
client.unsubscribeConfiguration("configstore", subscriptionId).block();
```

---

## Distributed Lock

**API**: `/v1.0-alpha1/lock` — **Alpha**

Named mutex locks with lease-based automatic expiration for cross-instance mutual exclusion.

### How It Works
1. Application requests lock by name with a TTL (lease duration in seconds)
2. Only one instance across the cluster holds the named lock at a time
3. Lock is released explicitly or automatically when the lease expires (prevents deadlocks from crashes)

### Use Cases
- Exclusive database row/table access
- Singleton job execution (ensure only one instance runs a task)
- Sequential queue message processing
- Critical section across distributed instances

### Usage (HTTP API)
```
POST /v1.0-alpha1/lock/<store-name>
{ "resourceId": "my-resource", "lockOwner": "my-instance-id", "expiryInSeconds": 30 }

DELETE /v1.0-alpha1/unlock/<store-name>
{ "resourceId": "my-resource", "lockOwner": "my-instance-id" }
```

---

## Cryptography

**API**: `/v1.0-alpha1/crypto` — **Alpha**

Perform cryptographic operations without the application ever seeing raw key material.

### Key Principle

The application requests operations (encrypt/decrypt) but never receives the raw key. Keys live in a vault or the Dapr engine — the application only gets the ciphertext or plaintext result.

### Component Types

**Vault-based**: Operations performed inside an external KMS (e.g., Azure Key Vault). Dapr proxies to the vault.

**Dapr-engine**: Operations performed inside the Dapr sidecar itself. Keys stored separately (files, Kubernetes Secrets, etc.) and loaded into the engine.

### Capabilities
- Encrypt / decrypt (AES-CBC, RSA-OAEP, etc.)
- Sign / verify (RSA-PSS, ECDSA, etc.)
- Stream processing for large files
- gRPC API recommended over HTTP for streaming
- The Dapr Crypto Scheme v1 standard

### Security Benefits
- Algorithm guardrails prevent use of weak ciphers
- Key rotation without application restarts
- Compliance-ready (GDPR, PCI-DSS, financial regulations)

---

## Jobs

**API**: `/v1.0-alpha1/jobs` — **Alpha**

Schedule work for future execution with at-least-once delivery guarantees.

### Architecture
- **Jobs API building block**: the scheduling interface
- **Scheduler control plane service**: manages job storage in embedded Etcd
- Jobs are **never executed before** their scheduled time (but may be slightly delayed)

### Multi-Replica Guarantees
The Scheduler service ensures that even with multiple replicas, only one Scheduler triggers each job at the scheduled time.

### Usage

```java
// Schedule a job
ScheduleJobRequest req = new ScheduleJobRequest("backup-job")
    .setSchedule("@every 1h")      // cron-like or "in X duration"
    .setData(jobPayload);
client.scheduleJob(req).block();

// Retrieve
client.getJob("backup-job").block();

// Delete
client.deleteJob("backup-job").block();
```

By default, scheduling a job with an existing name fails. Set `overwrite: true` to update.

### Use Cases
- Hourly/daily database backups
- ETL pipeline triggers
- Scheduled notification emails
- Off-hours maintenance tasks
- Batch financial settlements

---

## See Also

- [Dapr Overview](dapr-overview.md)
- [Dapr Building Blocks](dapr-building-blocks.md)
- [Dapr Java SDK](dapr-java-sdk.md)
- [Dapr Pub/Sub](dapr-pub-sub.md)
