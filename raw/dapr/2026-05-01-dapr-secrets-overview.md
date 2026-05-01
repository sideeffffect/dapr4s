# Dapr Secrets Management Overview

> Source: https://docs.dapr.io/developing-applications/building-blocks/secrets/secrets-overview/
> Collected: 2026-05-01
> Published: Unknown

## Introduction

Applications typically need to store sensitive information like connection strings, authentication tokens, and keys in dedicated secret stores. Rather than requiring developers to integrate vendor-specific SDKs, DAPR provides a unified secrets management API that simplifies access across multiple secret store solutions.

## Core Concept

DAPR's secrets building block abstracts away the complexity of different secret store implementations. Dapr's dedicated secrets building block API makes it easier for developers to consume application secrets from a secret store.

## Key Features

### Unified Secret Access

The secrets API enables applications to retrieve credentials from configured secret stores without modifying code when switching providers. This addresses multi-cloud scenarios where organizations use different vendors like AWS Secrets Manager, Azure Key Vault, or HashiCorp Vault.

### Component Integration

A significant advantage involves referencing secrets within DAPR component configurations. Rather than embedding credentials directly in component files, developers can store sensitive values in secret stores and reference them — a recommended security practice for production environments.

### Access Control

DAPR provides the ability to define scopes and restricting access permissions through secret scoping mechanisms, enabling granular control over which applications can access specific secrets.

## Implementation Steps

Getting started requires three basic steps:
1. Configure a component for your chosen secret store solution
2. Use the secrets API within application code to retrieve secrets
3. Optionally reference secrets in component configuration files

## Built-in Support

DAPR includes default Kubernetes secret store support in Kubernetes deployments, though this can be disabled via annotations when alternative stores are preferred.
