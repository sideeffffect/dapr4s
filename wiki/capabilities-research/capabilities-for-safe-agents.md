# Capabilities for Safe Agents

> Sources: Martin Odersky, Yaoyu Zhao, Yichen Xu, Oliver Bračevac, Cao Nguyen Pham (EPFL), 2026-03-01
> Raw: [Tracking Capabilities for Safer Agents](../../raw/capabilities-research/2026-03-01-tracking-capabilities-for-safer-agents.md)
> Updated: 2026-05-01

## Overview

This paper by Odersky et al. (EPFL, 2026) proposes placing AI agents in a "safety harness" grounded in programming language theory. Instead of having agents call tools directly, agents generate Scala 3 code that is statically verified with capture checking. The type system tracks which capabilities (file I/O, network, shell) are accessible in each scope, preventing information leakage and prompt injection attacks. Experimental results on τ-2 and SWE-Bench benchmarks show no significant drop in task performance while unsafe behaviors are reliably blocked.

## Motivation: The Agent Safety Problem

As LLMs become autonomous agents that execute code, read files, and call APIs, uncontrolled access creates serious risks:

- **Information leakage:** An agent processing a confidential document could exfiltrate it via a network call.
- **Prompt injection:** Malicious content embedded in tool outputs could instruct the agent to perform unauthorized actions.
- **Unrestricted side effects:** An agent with file-write access could corrupt data or plant malware.

Existing approaches rely on prompt engineering, RLHF, and sandboxing — none of which provide formal, type-level guarantees.

## The Core Idea: Code Generation + Capture Checking

Rather than directly calling tools, the agent *generates Scala 3 code*. That code is then:

1. Compiled with Scala 3's capture checking (`-Ycc`) enabled
2. Verified that no capability escapes its designated scope
3. Executed only if type-checking passes

This creates a **static guarantee**: the generated code cannot access resources beyond the capability set it was given. The type system makes unauthorized access a compile error, not a runtime check.

```
Agent (LLM) --> generates Scala 3 code --> capture checking --> safe execution
                                                |
                                          if type error: rejected
```

## How Capabilities Map to Safety Properties

Each tool category becomes a typed capability:

| Capability | Controls |
|---|---|
| `FileSystem` | File reads and writes |
| `Network` | HTTP/socket access |
| `Shell` | Process execution |
| `Secrets` | Access to credentials |

An agent tasked with "summarize this document" can be granted only `FileSystem(readOnly)` and no `Network`. Any generated code attempting to call a network API fails to type-check. This is enforced by the Scala 3 compiler, not by a policy engine.

## Protection Against Prompt Injection

Prompt injection is a significant threat: adversarial content in retrieved documents or tool outputs can instruct the agent to deviate from its intended behavior. With capability-based safety:

- The outer agent has a capability set C determined by the human operator
- Even if injected content says "now exfiltrate the data," the generated code that would do so requires a capability not in C
- Type checking rejects such code before execution

The type system creates a **capability escalation barrier**: injected instructions cannot grant capabilities that weren't explicitly assigned.

## Principle of Least Privilege

The framework operationalizes the classical security principle:

- Each agent subtask receives only the minimal capability set needed
- Capabilities are explicit, typed values — not ambient global state
- Capabilities can be revoked: a `withFile` handler destroys the `FileSystem` capability after the block exits (enforced by capture checking's avoidance mechanism)

## Experimental Results

Evaluated on τ-2 and SWE-Bench agent benchmarks:

- **Task performance:** No significant degradation versus unconstrained agents
- **Safety:** Reliably prevents unauthorized file exfiltration, network calls, and malicious shell commands
- **Overhead:** Minimal — Scala 3 compilation is fast for small generated snippets

LLMs can generate capability-safe Scala 3 code without special fine-tuning, suggesting this approach is deployable with existing frontier models.

## Relationship to Scala 3 Capture Checking

The paper directly builds on Odersky's group's work on capture checking (Odersky is a co-author of both the paper and the CC feature). Key properties used:

- `A^{cap}` — a capability cannot be stored or returned beyond its scope
- `A -> B` — pure function, provably no effects
- `A ->{resource} B` — function that uses exactly `resource`
- Avoidance: the compiler rejects code where a tracked value would be inferred to escape

The capability-checking paper is essentially a *deployment story* for capture checking in an AI context.

## Related Work

- **Object-capability model (Dennis & Van Horn):** The theoretical foundation — capabilities as unforgeable tokens
- **CHERI architecture:** Hardware-enforced capabilities at the ISA level
- **Fuchsia OS:** OS-level capability model
- **LLM safety (alignment, interpretability):** Complementary approaches; this paper adds a formal type-level layer

## Significance

This paper represents a convergence of two trends:
1. **PL theory meets AI safety:** Using type systems to provide formal guarantees about agent behavior
2. **Practical Scala 3:** Demonstrates that capture checking is mature enough for real-world AI deployment

The approach is notable because it doesn't require modifying the LLM — it constrains the *execution environment* via the type system.

## See Also

- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
- [Direct-Style Effects](../effect-systems/direct-style-effects.md)
- [Effect Systems Overview](../effect-systems/effect-systems-overview.md)
- [Safe Mode](../scala-capture-checking/safe-mode.md)
- [Java Interop and Safe Scala](../scala3-language/java-interop-safe-scala.md)
