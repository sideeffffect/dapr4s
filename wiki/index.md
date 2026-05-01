# Knowledge Base Index

## effect-systems

Effect tracking, direct-style programming, capabilities, and related Scala 3 mechanisms.

| Article | Summary | Updated |
|---|---|---|
| [Effect Systems Overview](effect-systems/effect-systems-overview.md) | What effect systems are, the design space (CPS vs direct-style, monadic vs capability-based, tagless final) | 2026-05-01 |
| [Direct-Style Effects](effect-systems/direct-style-effects.md) | Direct-style approach in Scala 3: capability-passing with `?=>`, `boundary`/`break` for control flow, comparison with monadic effects | 2026-05-01 |
| [Capability-Based Effects](effect-systems/capability-based-effects.md) | Capability-based effects in Scala 3: context functions, capture checking (`-Ycc`), capture sets, subtyping, escape prevention | 2026-05-01 |

## capabilities-research

Research on applying capability systems to AI agent safety.

| Article | Summary | Updated |
|---|---|---|
| [Capabilities for Safe Agents](capabilities-research/capabilities-for-safe-agents.md) | Odersky et al. (EPFL, 2026): placing AI agents in a Scala 3 capture-checking safety harness to prevent leakage and prompt injection | 2026-05-01 |

## scala-capture-checking

Official Scala 3 compiler documentation on capture checking — the experimental type system extension that tracks capabilities (effects) in types.

| Article | Summary | Updated |
|---|---|---|
| [Capture Checking Overview](scala-capture-checking/capture-checking-overview.md) | What CC is, motivation (resource leaks, lifetimes), enabling imports, capturing type syntax, escape checking | 2026-05-01 |
| [Capturing Types](scala-capture-checking/capturing-types.md) | `T^{c}` syntax, capture sets, subcapturing relation, function types, lazy vals, capture tunneling, implicit vs explicit polymorphism | 2026-05-01 |
| [Capabilities and Resources](scala-capture-checking/capabilities-and-resources.md) | What capabilities are, the capability hierarchy (Shared/Exclusive/Control/Mutable), global capabilities, class capture sets, resource lifetimes | 2026-05-01 |
| [Safe Exceptions](scala-capture-checking/safe-exceptions.md) | `CanThrow` capabilities, `throws` desugaring, `Try.apply` classifier filtering, escape prevention under CC | 2026-05-01 |
| [Separation and Mutability](scala-capture-checking/separation-and-mutability.md) | `Mutable`/`Stateful`/`Unscoped`, `update` methods, separation checking, `consume` parameters, `freeze`, `fresh` | 2026-05-01 |
| [Capability Classifiers](scala-capture-checking/capability-classifiers.md) | Classifier trait hierarchy, `.only[C]` projection, access-control brand pattern, predefined classifiers | 2026-05-01 |
| [Safe Mode](scala-capture-checking/safe-mode.md) | Restricted language subset for agent code: six restrictions, `@assumeSafe`/`@rejectSafe`, exception handling, TACIT use case | 2026-05-01 |
| [How to Use Capture Checking](scala-capture-checking/how-to-use.md) | Enabling imports, SBT template, compiler flags (`-Vprint:cc`, `-Ycc-verbose`, `-Ycc-debug`), internals overview | 2026-05-01 |

## dapr

Dapr (Distributed Application Runtime) — portable, event-driven runtime for building resilient microservices; covers architecture, building blocks, Java SDK, and testing.

| Article | Summary | Updated |
|---------|---------|---------|
| [Dapr Overview](dapr/dapr-overview.md) | What Dapr is, sidecar pattern, architecture layers, security model, SDK support | 2026-05-01 |
| [Dapr Building Blocks](dapr/dapr-building-blocks.md) | All 13 building blocks with descriptions, API endpoints, and stability status | 2026-05-01 |
| [Dapr Service Invocation](dapr/dapr-service-invocation.md) | Service-to-service calls via App ID, name resolution, mTLS, retries, streaming | 2026-05-01 |
| [Dapr State Management](dapr/dapr-state-management.md) | Key/value state, OCC/ETags, transactions, TTL, encryption, outbox pattern | 2026-05-01 |
| [Dapr Pub/Sub](dapr/dapr-pub-sub.md) | Async messaging, CloudEvents, at-least-once delivery, consumer groups, subscriptions | 2026-05-01 |
| [Dapr Actors](dapr/dapr-actors.md) | Virtual actor pattern, turn-based access, placement service, timers vs reminders | 2026-05-01 |
| [Dapr Workflows](dapr/dapr-workflows.md) | Durable workflow orchestration, event sourcing, determinism requirement, activities | 2026-05-01 |
| [Dapr Java SDK](dapr/dapr-java-sdk.md) | Java SDK structure, DaprClient API, actors SDK, workflows SDK, key usage patterns | 2026-05-01 |
| [Dapr Testcontainers](dapr/dapr-testcontainers.md) | Integration testing with DaprContainer, component/subscription setup, JUnit 5 patterns | 2026-05-01 |
| [Dapr Other Building Blocks](dapr/dapr-other-building-blocks.md) | Bindings, secrets, configuration, distributed lock, cryptography, jobs | 2026-05-01 |
