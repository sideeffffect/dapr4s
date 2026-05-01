# Tracking Capabilities for Safer Agents

> Source: https://arxiv.org/abs/2603.00991
> Collected: 2026-05-01
> Published: 2026-03-01

## Metadata

**Authors:** Martin Odersky, Yaoyu Zhao, Yichen Xu, Oliver Bračevac, Cao Nguyen Pham
**Affiliation:** EPFL, Lausanne, Switzerland
**DOI:** https://doi.org/10.48550/arXiv.2603.00991
**Categories:** cs.AI, cs.PL
**License:** CC BY 4.0

## Abstract

The paper addresses safety concerns in AI agents by placing them in a "safety harness" built on programming language principles. Rather than directly calling tools, agents generate code in Scala 3 with capture checking — a capability-safe language. The type system tracks capabilities statically to regulate access to effects and resources. Key benefits include preventing information leakage through local purity enforcement and protection against prompt injection attacks. Experimental results demonstrate agents can generate capability-safe code with no significant loss in task performance, while the type system reliably prevents unsafe behaviors such as information leakage and malicious side effects.

## 1. Introduction

As language models become increasingly autonomous, controlling what resources and operations agents can access becomes critical. The work motivates the need for safer agent design as LLMs gain autonomous execution abilities. Current approaches to agent safety are limited regarding resource access control, operation authorization, and capability restrictions without breaking functionality.

The authors argue that capability tracking provides a foundation for safer agent design by explicitly controlling what actions agents can perform.

## 2. Background & Motivation

### Agent Safety Challenges

Key vulnerabilities in autonomous agents:
- Unrestricted file system access
- Unchecked network operations
- Uncontrolled code execution capabilities
- Data exfiltration risks

### Existing Approaches

The research builds on prior work in:
- **Capability-based security:** Object capability models from systems literature
- **LLM safety research:** Recent work on agent oversight and verification
- **Programming language security:** Type systems and access control mechanisms
- **Scala 3 capture checking:** The CC (capture checking) experimental feature in Scala 3

## 3. Technical Approach

### Core Concept: Capability Tracking via Scala 3 Capture Checking

Rather than having agents directly call tools (file I/O, network, shell), the framework has agents generate Scala 3 code. That code is then type-checked with capture checking enabled. The type system tracks which capabilities are required by each function, preventing unsafe capability escape.

**Type-based capability representation:** Capabilities are tracked as first-class values that must be explicitly passed to operations. This prevents implicit access to sensitive resources.

**Capability propagation:** Operations can only access resources they've been explicitly granted through their function parameters. The type system enforces this statically.

### Implementation Details

- Capabilities modeled as opaque types in Scala 3
- Operations require explicit capability arguments (using the `using` clause)
- Capture checking (`-Ycc` flag) verifies at compile time that capabilities don't escape their scope
- Granular control over specific resource types (FileSystem, Network, Shell, etc.)

### Key Design Principles

1. **Principle of least privilege:** Agents receive minimal necessary capabilities
2. **Explicit authorization:** All resource access requires capability tokens
3. **Revocability:** Capabilities can be dynamically revoked
4. **Composability:** Complex operations built from capability-restricted components
5. **Local purity enforcement:** Code regions can be proven pure when they lack effect-granting capabilities

### Safety Against Prompt Injection

One particularly important use case: when an agent receives adversarial instructions embedded in tool outputs (prompt injection), the capability type system prevents the injected instructions from gaining capabilities the outer agent wasn't supposed to grant. The type system makes capability escalation statically impossible.

## 4. System Architecture

### Components

**Capability system:** Tracks permissions through the agent's execution lifecycle. Operations are restricted based on available capabilities.

**Authorization layer:** Validates capability possession before executing privileged operations.

**Code generation pipeline:** Agent generates Scala 3 code → capture checking validates → safe execution.

**Audit trail:** Logs capability usage for oversight and debugging.

### Integration with Agents

Agents receive a capability set upon initialization. All generated code must type-check with these capabilities, enabling:
- Static verification of capability requirements
- Runtime enforcement of access restrictions
- Clear dependency tracking

## 5. Experiments & Evaluation

### Benchmarks

Evaluation on: τ-2, SWE-Bench variations, and other realistic agent benchmarks.

### Key Findings

- Capability tracking and restrictions impose minimal performance overhead on task completion
- Agents can generate capability-safe Scala 3 code without significant degradation in task success rate
- The type system reliably prevents common exploit patterns: information leakage, malicious side effects
- Clear visibility into resource usage through capability tracking

## 6. Related Work

### Capability-Based Security

Building on Dennis & Van Horn's foundational work in object capabilities, applied to modern AI contexts. Also relates to:
- CHERI architecture (hardware capability enforcement)
- Fuchsia OS capability model
- Object-capability model (OCM) in programming languages

### LLM Safety

Connects to recent research on agent alignment, interpretability, and oversight mechanisms.

### Scala 3 Capture Checking

Directly uses the `-Ycc` capture checking feature co-developed by Odersky's group at EPFL. The CC system tracks captured values in function types, enabling the type system to reason about effect scope.

## 7. Conclusion

Capability tracking via Scala 3's type system offers a practical, principled approach to agent safety. By making capabilities explicit and type-checked, the framework:

- Reduces unauthorized resource access risks
- Maintains agent usefulness for legitimate operations
- Provides foundation for composable safety mechanisms
- Enables auditing and oversight of agent behavior
- Prevents prompt injection attacks through static capability analysis

The work positions capability-based, type-checked security as essential infrastructure for deploying autonomous AI agents responsibly.
