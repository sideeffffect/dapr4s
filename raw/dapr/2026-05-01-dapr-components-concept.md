# Dapr Components Concept

> Source: https://docs.dapr.io/concepts/components-concept/
> Collected: 2026-05-01
> Published: Unknown

## Overview

Components form the modular foundation of Dapr's architecture. They are described as "modular functionality used by building blocks and applications." These interchangeable units enable developers to swap implementations while maintaining consistent interfaces.

## Component Specification

Components follow standardized specifications defined in YAML configuration files. These files typically reside in a `components/local` folder or globally in the `.dapr` directory. The component spec values, particularly the spec `metadata`, can change between components of the same component type.

## Built-In vs. Pluggable Components

**Built-in components** ship with Dapr and originate from community contributions. **Pluggable components** are privately-hosted alternatives that exist outside the runtime. Pluggable components suit scenarios where "your component may be specific to your company or pose IP concerns, so it cannot be included in the Dapr component repo."

## Hot Reloading Feature

When enabled, components can be updated without restarting the runtime. Updates occur when component resources are created, modified, or deleted — either through Kubernetes APIs or file changes in the `resources` directory.

## Component Types

1. **Name Resolution** - Enables service-to-service discovery
2. **Pub/Sub Brokers** - Facilitate publish-subscribe messaging
3. **State Stores** - Persist key-value data
4. **Bindings** - Connect external resources
5. **Secret Stores** - Safeguard sensitive information
6. **Configuration Stores** - Manage application settings
7. **Locks** - Provide distributed mutual exclusion
8. **Cryptography** - Handle encryption operations
9. **Conversation** - Abstract LLM interactions
10. **Middleware** - Process HTTP requests

Each category references corresponding implementations available in the components-contrib repository.
