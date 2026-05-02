# Knowledge Base Index

## effect-systems

Effect tracking, direct-style programming, capabilities, and related Scala 3 mechanisms.

| Article | Summary | Updated |
|---|---|---|
| [Effect Systems Overview](effect-systems/effect-systems-overview.md) | What effect systems are, the design space (CPS vs direct-style, monadic vs capability-based, tagless final) | 2026-05-01 |
| [Direct-Style Effects](effect-systems/direct-style-effects.md) | Direct-style approach in Scala 3: capability-passing with `?=>`, `boundary`/`break` for control flow, comparison with monadic effects | 2026-05-01 |
| [Capability-Based Effects](effect-systems/capability-based-effects.md) | Capability-based effects in Scala 3: context functions, capture checking (`-Ycc`), capture sets, subtyping, escape prevention | 2026-05-01 |

## capabilities-research

Foundational research on capability-based type systems, algebraic effects, and applying capabilities to AI agent safety.

| Article | Summary | Updated |
|---|---|---|
| [Capabilities for Safe Agents](capabilities-research/capabilities-for-safe-agents.md) | Odersky et al. (EPFL, 2026): placing AI agents in a Scala 3 capture-checking safety harness to prevent leakage and prompt injection | 2026-05-01 |
| [Reach Capabilities](capabilities-research/reach-capabilities.md) | rcaps and System Capless: capability tracking through generic data structures (OOPSLA 2025) | 2026-05-01 |
| [CC Calculus](capabilities-research/cc-calculus.md) | CF<: calculus: the formal foundation for Scala 3 capture checking (TOPLAS 2023) | 2026-05-01 |
| [Scoped Capabilities for Polymorphic Effects](capabilities-research/scoped-capabilities-polymorphic-effects.md) | CCsubBox: effects via captured variables, effect polymorphism via capture variables (arXiv 2022) | 2026-05-01 |
| [Polymorphic Reachability Types](capabilities-research/polymorphic-reachability-types.md) | λ◇ and F<:◇: sound reachability polymorphism for aliasing and separation (2023) | 2026-05-01 |
| [Algebraic Effects and Handlers](capabilities-research/algebraic-effects-handlers.md) | Plotkin/Pretnar foundational framework: operations, handlers, continuations, algebraic laws (ESOP 2009) | 2026-05-01 |

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

## scala3-language

Core Scala 3 language features for safe library design: opaque types, context functions, given/using, Scala CLI build tool, and Java interop with Safe Scala.

| Article | Summary | Updated |
|---------|---------|---------|
| [Opaque Types](scala3-language/opaque-types.md) | Zero-cost type abstraction for wrapping Java/primitive types; companion object smart constructors, type bounds, extension methods | 2026-05-01 |
| [Context Functions and Capability Passing](scala3-language/context-functions-capability-passing.md) | `A ?=> B` syntax, automatic expansion, capability threading, DSL builder pattern, OxDispatcher, postcondition pattern | 2026-05-01 |
| [Given Instances and Using Clauses](scala3-language/given-using.md) | `given`/`using` syntax, conditional givens, alias givens, initialization semantics, summoning capabilities, Scala 2 migration | 2026-05-01 |
| [Scala CLI as Build Tool](scala3-language/scala-cli-build-tool.md) | Using directives, Java/Scala deps, experimental compiler flags (`-language:experimental.safe`/`captureChecking`), library project layout | 2026-05-01 |
| [Java Interop and Safe Scala](scala3-language/java-interop-safe-scala.md) | `@assumeSafe`/`@rejectSafe`, wrapping Java SDK calls behind capability boundaries, trusted vs untrusted code zones, CC integration | 2026-05-01 |

## scala-effect-libraries

Kyo, Ox, Effekt, and related Scala effect libraries — design approaches, algebraic effects, capability-passing, structured concurrency, and comparison for DAPR wrapper design.

| Article | Summary | Updated |
|---------|---------|---------|
| [Kyo Effects](scala-effect-libraries/kyo-effects.md) | Kyo's `A < S` Pending type, open algebraic effect set, effect widening, direct syntax, cross-platform support | 2026-05-01 |
| [Ox Structured Concurrency](scala-effect-libraries/ox-structured-concurrency.md) | Ox's local-safety approach, `supervised` scopes, `par`/`race`/`timeout`, `either:` error handling, capture checking integration | 2026-05-01 |
| [Effekt Capability Passing](scala-effect-libraries/effekt-capability-passing.md) | Capability-passing style for extensible effect handlers: lexical scoping, type safety without monad transformers, Scala library history | 2026-05-01 |
| [Scala Caps Capability](scala-effect-libraries/scala-caps-capability.md) | `scala.caps.Capability` marker trait: what it means to extend it, `^` notation, capture sets, the `scala.caps` hierarchy | 2026-05-01 |
| [Scala Effect Libraries Comparison](scala-effect-libraries/scala-effect-libraries-comparison.md) | Kyo/Ox/Effekt/ZIO/Cats Effect: design approach, capture checking integration, composability, DAPR wrapper recommendations | 2026-05-01 |
| [Gears Async](scala-effect-libraries/gears-async.md) | EPFL experimental direct-style async library: `Async` capability, structured concurrency, Future types, capture checking integration, comparison with Ox | 2026-05-01 |

## scala-type-safety

Type-driven design patterns for correct, self-documenting Scala code: ADTs, opaque types, parse-don't-validate, and eliminating primitive obsession.

| Article | Summary | Updated |
|---------|---------|---------|
| [Parse, Don't Validate](scala-type-safety/parse-dont-validate.md) | Alexis King's principle: encode validation results in types rather than discarding them; stratify programs into a parsing phase and an execution phase | 2026-05-02 |
| [Primitive Obsession and Opaque Types](scala-type-safety/primitive-obsession-opaque-types.md) | Using raw String/Int for domain values; opaque types as zero-cost wrappers; smart constructors; vs. case classes and value classes | 2026-05-02 |
| [ADTs and Making Illegal States Unrepresentable](scala-type-safety/adts-illegal-states.md) | Sealed hierarchies, final subtypes, case-class discipline, enum vs. Enumeration; nrinaudo best-practice rules for exhaustive ADTs | 2026-05-02 |
| [Scala Best Practices (nrinaudo)](scala-type-safety/scala-best-practices-nrinaudo.md) | Complete reference: numeric pitfalls, sealed/final rules, unsafe partial ops (head/get/reduce), referential transparency, ADT conventions, binary compat | 2026-05-02 |

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
| [Dapr Testcontainers](dapr/dapr-testcontainers.md) | Integration testing with DaprContainer, component/subscription setup, host app channel, QuotedBoolean, placement container, JUnit patterns | 2026-05-01 |
| [Dapr Other Building Blocks](dapr/dapr-other-building-blocks.md) | Bindings, secrets, configuration, distributed lock, cryptography, jobs | 2026-05-01 |
| [Dapr Resiliency](dapr/dapr-resiliency.md) | Timeout, retry (constant/exponential), and circuit breaker policies; per-app, per-actor, per-component binding via named policy specs | 2026-05-01 |
| [Dapr Workflow Patterns](dapr/dapr-workflow-patterns.md) | Task chaining, fan-out/fan-in, async HTTP APIs, monitor (eternal) pattern, external event / human approval, child workflows, compensation | 2026-05-01 |
| [Dapr Actors Deep Dive](dapr/dapr-actors-deep-dive.md) | Virtual actor lifecycle, turn-based concurrency, reentrancy, reminders vs timers, placement service, state persistence, AI agent use cases | 2026-05-01 |
| [Dapr Pluggable Components](dapr/dapr-pluggable-components.md) | Custom state stores, pub/sub, and bindings via gRPC/UDS; multi-interface components; independent release cycle | 2026-05-01 |
| [Dapr Java SDK — Virtual Threads](dapr/dapr-java-sdk-virtual-threads.md) | SDK internals (gRPC/Netty transport, newGrpcStub() bypass, ThreadlessExecutor, Mono.block() vs toFuture().get() VT safety) | 2026-05-02 |
