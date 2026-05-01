# Dapr Building Blocks Concept

> Source: https://docs.dapr.io/concepts/building-blocks-concept/
> Collected: 2026-05-01
> Published: Unknown

## Overview

Building blocks represent the core architectural pattern of Dapr. A building block is an HTTP or gRPC API that can be called from your code and uses one or more Dapr components.

## Key Characteristics

The building blocks architecture serves three primary purposes:

1. **API Accessibility** - Exposed through standard HTTP or gRPC interfaces
2. **Problem Resolution** - Address common challenges in microservices development
3. **Best Practice Codification** - Implement established patterns and practices

## Complete Building Blocks Reference

### Service-to-Service Invocation
**Endpoint:** `/v1.0/invoke`

Enables applications to communicate through well-known endpoints using HTTP or gRPC. The system provides reverse proxy functionality combined with integrated service discovery, distributed tracing, and error handling capabilities.

### Publish and Subscribe
**Endpoint:** `/v1.0/publish` `/v1.0/subscribe`

Implements loosely coupled messaging where publishers send messages to topics and subscribers receive them.

### Workflows
**Endpoint:** `/v1.0/workflow`

Defines long-running, persistent processes spanning multiple microservices. These can integrate with other Dapr APIs for enhanced flexibility.

### State Management
**Endpoint:** `/v1.0/state`

Provides key/value state preservation with pluggable store backends for application persistence requirements.

### Bindings
**Endpoint:** `/v1.0/bindings`

Creates bidirectional connections to external services, supporting both invocation and event-triggered responses.

### Actors
**Endpoint:** `/v1.0/actors`

Implements the virtual actor pattern providing isolated compute units with single-threaded execution and automatic garbage collection.

### Secrets
**Endpoint:** `/v1.0/secrets`

Manages secret retrieval from integrated store providers, supporting cloud, local, and Kubernetes storage options.

### Configuration
**Endpoint:** `/v1.0/configuration`

Retrieves and monitors application configuration items with real-time change subscriptions.

### Distributed Lock
**Endpoint:** `/v1.0-alpha1/lock`

Provides resource locking mechanisms ensuring consistency across multiple application instances.

### Cryptography
**Endpoint:** `/v1.0-alpha1/crypto`

Performs cryptographic operations without exposing keys to applications.

### Jobs
**Endpoint:** `/v1.0-alpha1/jobs`

Schedules and orchestrates batch processing, maintenance tasks, and ETL operations.

### Conversation
**Endpoint:** `/v1.0-alpha2/conversation`

Enables LLM interaction with features including prompt caching, response formatting, and PII obfuscation.

## Architecture Pattern

The building blocks expose public APIs that utilize underlying Dapr components for implementation, creating a layered abstraction supporting microservices development patterns.
