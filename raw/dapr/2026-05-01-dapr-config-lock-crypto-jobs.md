# Dapr Configuration, Distributed Lock, Cryptography, and Jobs Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/configuration/configuration-api-overview/ ; https://docs.dapr.io/developing-applications/building-blocks/distributed-lock/distributed-lock-api-overview/ ; https://docs.dapr.io/developing-applications/building-blocks/cryptography/cryptography-overview/ ; https://docs.dapr.io/developing-applications/building-blocks/jobs/jobs-overview/
> Collected: 2026-05-01
> Published: Unknown

---

## Configuration API

### Core Concept

The Configuration API is a Dapr building block enabling applications to consume and manage configuration data dynamically. Consuming application configuration is a common task when writing applications.

### Key Capabilities

The API allows developers to:
- Retrieve configuration items that are returned as read-only key/value pairs
- Subscribe to notifications when configuration values change
- Access data stored in configuration stores at runtime

### Common Use Cases

Configuration items typically include:
- Secret identifiers and names
- Application-specific IDs (partition, consumer)
- Database connection references
- Other runtime parameters

### Important Distinction

The Configuration API differs fundamentally from Dapr's operational configuration. The latter controls policies and settings on Dapr sidecar instances or the installed Dapr control plane, whereas the Configuration API focuses on application-level data management.

### Data Management Model

Configuration operates as read-only from the application perspective. Updates occur through operational tooling rather than application APIs, maintaining separation of concerns between application logic and configuration governance.

---

## Distributed Lock

### Introduction

Distributed locks in DAPR provide mechanisms for mutually exclusive access to a resource. They enable coordination across multiple application instances that need to safely access shared data.

### Primary Use Cases

- Exclusive access to database elements (rows, tables, or entire databases)
- Sequential message processing from queues
- Any shared resource requiring coordinated updates

The system uses named locks, where the application determines the resources that the named lock accesses.

### Core Architecture

Locks operate on an application-scoped basis. At any moment, only one instance can hold a particular named lock. Multiple instances of the same application use this named lock to exclusively access the resource.

### Lease-Based Deadlock Prevention

DAPR implements automatic lock expiration through leases. If an application acquires a lock but fails to release it (due to crashes or exceptions), the system automatically releases the lock after a timeout period. This prevents permanent deadlocks from application failures.

### Lock Release Mechanisms

Locks can be freed through two methods:
1. Explicit API calls by the application
2. Automatic expiration via lease timeout

### Current Status

The API is designated as being in "Alpha" state, indicating ongoing development and potential future changes.

---

## Cryptography

### Main Concept

Dapr's cryptography building block enables secure cryptographic operations while keeping encryption keys hidden from applications. Applications never see the 'raw key material', but can request the vault to perform operations with the keys.

### Why Cryptography Matters

The platform addresses critical security challenges:
- **Algorithm Selection**: Dapr implements safeguards against unsafe cryptographic choices
- **Key Protection**: Raw key material remains isolated from application code
- **Compliance**: Supports regulatory requirements like GDPR and financial industry standards
- **Operational Efficiency**: Keys can be rotated without application restarts or developer involvement

### Two Component Types

**Vault-Based Components**: These interact with external key management systems like Azure Key Vault, performing cryptographic operations within secure vaults rather than in Dapr itself.

**Dapr Engine Components**: These leverage Dapr's own cryptographic engine, performing operations within the sidecar while storing keys separately (files, Kubernetes secrets, etc.).

### Key Features

- Encryption and decryption capabilities
- Support for RSA and AES key types
- Stream-based processing for large files
- The Dapr Crypto Scheme v1 standard
- Both HTTP and gRPC API support (gRPC recommended)

---

## Jobs

### Overview

The jobs API serves as an orchestrator for scheduling future jobs at specific times or intervals. Internally, Dapr leverages the Scheduler service to manage actor reminders alongside job scheduling capabilities.

The jobs system comprises two core components:
- The jobs API building block
- The Scheduler control plane service

### How It Works

The jobs API functions as a job scheduler rather than an executor. Its design emphasizes "at least once" job execution, prioritizing durability and horizontal scaling over precision.

Key guarantees:
- **Guaranteed**: A job is never invoked before the schedule time is due
- **Not guaranteed**: A specific ceiling time for job invocation after the due time arrives

Job details and user-associated data are stored in an embedded Etcd database within the Scheduler service.

### Primary Use Cases

1. Delaying pub/sub messaging to future times (specific dates/UTC times)
2. Scheduling service invocation method calls between applications

### Scenarios

- **Automated Database Backups**: Daily backup scripts at designated times
- **Data Processing/ETL**: Regular transformation and loading of data from multiple sources
- **Email Notifications**: Scheduled reports and summaries at specific intervals
- **Maintenance Tasks**: Off-peak system updates and health checks
- **Batch Financial Processing**: End-of-day transaction settlements

### Features

The primary functionality enables creating, retrieving, and deleting scheduled jobs. By default, creating a job with an existing name fails unless the `overwrite` flag is explicitly set to `true`.

**Multi-Replica Scheduling**: The Scheduler service guarantees that jobs scale across multiple replicas while ensuring only one Scheduler instance triggers each job.
