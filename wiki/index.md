# Knowledge Base Index

## testing

Docker-based integration testing with Testcontainers — general architecture, Scala-specific wrappers, and Dapr integration.

| Article | Summary | Updated |
|---|---|---|
| [Testcontainers Overview](testing/testcontainers-overview.md) | Core architecture, Ryuk cleanup, wait strategies, port mapping, networking (container-to-container, container-to-host), reuse, configuration | 2026-05-05 |
| [Testcontainers-Scala](testing/testcontainers-scala.md) | Scala wrapper: TestContainerForAll/ForEach, TestContainersForAll/ForEach, ContainerDef vs Container, GenericContainer, MUnit fixtures, 40+ modules, SBT setup | 2026-05-05 |

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

Dapr (Distributed Application Runtime) — portable, event-driven runtime for building resilient microservices; covers architecture, building blocks, Java and JS SDKs, and testing.

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
| [Dapr JS SDK](dapr/dapr-js-sdk.md) | @dapr/dapr 3.18.0 API map: CommonJS/named root exports, DaprClient sub-clients, per-protocol support matrix, GrpcEndpoint scheme bug, DaprServer, actors (class-name reflection hazard), workflows (async-generator model, executor driving rules, deterministic-UUID gap, *WithName variants, isFirstAttempt worker-reconnect bug — daprd restart permanently kills the worker), serialization/error rules (incl. Redis integer-etag 400-vs-409), missing jobs/conversation | 2026-06-12 |
| [Dapr Testcontainers](dapr/dapr-testcontainers.md) | Integration testing with DaprContainer, component/subscription setup, host app channel, QuotedBoolean, placement container, JUnit patterns, Spring Boot @ServiceConnection, multi-language | 2026-05-05 |
| [Dapr E2E — Self-Hosted (dapr run)](dapr/dapr-e2e-selfhosted.md) | `dapr run -- java -cp <assembly>` pattern for host-JVM E2E tests; Mill forkArgs+assembly() to avoid deadlocks; Scala 3 testcontainers self-referential generic workaround; why not DaprContainer | 2026-05-27 |
| [Dapr Other Building Blocks](dapr/dapr-other-building-blocks.md) | Bindings, secrets, configuration, distributed lock, cryptography, jobs | 2026-05-01 |
| [Dapr Resiliency](dapr/dapr-resiliency.md) | Timeout, retry (constant/exponential), and circuit breaker policies; per-app, per-actor, per-component binding via named policy specs | 2026-05-01 |
| [Dapr Workflow Patterns](dapr/dapr-workflow-patterns.md) | Task chaining, fan-out/fan-in, async HTTP APIs, monitor (eternal) pattern, external event / human approval, child workflows, compensation | 2026-05-01 |
| [Dapr Actors Deep Dive](dapr/dapr-actors-deep-dive.md) | Virtual actor lifecycle, turn-based concurrency, reentrancy, reminders vs timers, placement service, state persistence, AI agent use cases | 2026-05-01 |
| [Dapr Pluggable Components](dapr/dapr-pluggable-components.md) | Custom state stores, pub/sub, and bindings via gRPC/UDS; multi-interface components; independent release cycle | 2026-05-01 |
| [Dapr Java SDK — Virtual Threads](dapr/dapr-java-sdk-virtual-threads.md) | SDK internals (gRPC/Netty transport, newGrpcStub() bypass, ThreadlessExecutor, Mono.block() vs toFuture().get() VT safety) | 2026-05-02 |

## kubernetes

Local Kubernetes distributions for development, CI, and integration testing — comparison of k3d, kind, k0s, k3s, Minikube, MicroK8s; Dapr deployment and component configuration on Kubernetes.

| Article | Summary | Updated |
|---------|---------|---------|
| [Local Kubernetes Stacks](kubernetes/local-kubernetes-stacks.md) | k3d (v5.8.3), kind, k3s, k0s, Minikube, MicroK8s — comparison, k3d v5.x breaking changes, dev-mode Redis hostname, Dapr integration testing recipe | 2026-05-02 |
| [Dapr on Kubernetes](kubernetes/dapr-on-kubernetes.md) | Dapr control plane, sidecar injection, component/subscription CRDs with correct `dapr-dev-redis-master` hostname, distributed lock component, complete k3d+Dapr setup | 2026-05-02 |

## scala3-metaprogramming

Scala 3 metaprogramming and macros — the official reference (inline, compile-time ops, quotes/splices, TASTy reflection, runtime staging, TASTy inspection) plus the cross-version Scala-Hearth library.

| Article | Summary | Updated |
|---------|---------|---------|
| [Scala 3 Metaprogramming Overview](scala3-metaprogramming/metaprogramming-overview.md) | The six facilities, the static→dynamic spectrum, staging levels, and why it matters for trait derivation | 2026-06-07 |
| [Inline](scala3-metaprogramming/inline.md) | `inline def`/`val`/params, transparent inline, inline if/match, role as macro entry point | 2026-06-07 |
| [Compile-time Operations](scala3-metaprogramming/compile-time-operations.md) | `scala.compiletime`: constValue, erasedValue, summonInline/summonFrom, error; Mirror-based derivation context | 2026-06-07 |
| [Macros: Quotes and Splices](scala3-metaprogramming/macros-quotes-and-splices.md) | `Expr`/`Type`, quotes `'{}` & splices `${}`, PCP/level consistency, lifting/unlifting, quote pattern matching, `Expr.summon` | 2026-06-07 |
| [TASTy Reflection](scala3-metaprogramming/tasty-reflection.md) | `quotes.reflect`: Tree/Term/TypeRepr/Symbol/Flags, `Symbol.newClass`/`newMethod`, the canonical class-synthesis recipe | 2026-06-07 |
| [Runtime Staging & TASTy Inspection](scala3-metaprogramming/runtime-staging-and-tasty-inspection.md) | `scala.quoted.staging` run/withQuotes for runtime codegen; TASTy `Inspector` over `.tasty` files | 2026-06-07 |
| [Scala-Hearth](scala3-metaprogramming/scala-hearth.md) | Cross-version (Scala 2+3) macro standard library; cross-quotes plugin; derivation checklist | 2026-06-07 |

## scala-rpc-derivation

Scala 3 libraries that derive an implementation FROM a trait (RPC clients, routers, tagless algebras, proxies, DI wiring) — the landscape, the shared `quotes.reflect` mechanism, and how each qualifying library implements it.

| Article | Summary | Updated |
|---------|---------|---------|
| [Trait-to-Implementation Derivation Overview](scala-rpc-derivation/trait-to-impl-derivation-overview.md) | The pattern, the Scala-3 filter, mechanism taxonomy, tier table, excluded libraries, Mirror-derivation note | 2026-06-07 |
| [Derivation Mechanism Pattern](scala-rpc-derivation/derivation-mechanism-pattern.md) | The shared 5-step recipe (Symbol.newClass/newMethod/DefDef/ClassDef/New); what varies per library; sub-techniques | 2026-06-07 |
| [Sloth](scala-rpc-derivation/sloth.md) | `client.wire[T]` → reflect class synthesis; ClientImpl.execute serialize+transport; Co/Contra | 2026-06-07 |
| [Automorph](scala-rpc-derivation/automorph.md) | `client.bind[Api]` → macro bindings + JDK dynamic Proxy (name-keyed dispatch); not class synthesis | 2026-06-07 |
| [Oxygen (oxygen-http)](scala-rpc-derivation/oxygen-http.md) | `DeriveClient.derived[A]` → reflect class synthesis; HTTP via ZIO Client; `URLayer[Client, Api]`; @experimental | 2026-06-07 |
| [Spice](scala-rpc-derivation/spice.md) | `ApiClient.derive[T](baseUrl)` → reflect class synthesis; GET/RESTful/JSON by shape; `rapid.Task[R]`, fabric.rw | 2026-06-07 |
| [Kreuzberg RPC](scala-rpc-derivation/kreuzberg.md) | `makeStub[T]` (client) + `makeDispatcher` (server) via shared TraitAnalyzer; Scala.js; @experimental | 2026-06-07 |
| [smithy4s-deriving](scala-rpc-derivation/smithy4s-deriving.md) | `derives API` → operation-mirror + reflect; yields real smithy4s `Service`; @experimental, 3.4.1+, -Yretain-trees | 2026-06-07 |
| [ops-mirror](scala-rpc-derivation/ops-mirror.md) | `OpsMirror.Of[T]` — Mirror for a trait's operations; structural view only, consumer's `derived` synthesizes impl | 2026-06-07 |
| [zio-blocks RPC (PR #1270)](scala-rpc-derivation/zio-blocks-rpc.md) | `derives RPC` → reflect macro, but a metadata DESCRIPTOR `RPC[T]`, not a callable client; in-flight PR | 2026-06-07 |
| [cats-tagless](scala-rpc-derivation/cats-tagless.md) | `derives FunctorK` etc. → reflect `newClassOf` builds `Alg[G]` from `Alg[F]` + `F ~> G`; @experimental | 2026-06-07 |
| [tagless-redux](scala-rpc-derivation/tagless-redux.md) | `WireProtocol.derive` for tagless algebras (Kryo/Pekko/Boopickle); reflect rewrite of cats-tagless | 2026-06-07 |
| [ZIO IsReloadable](scala-rpc-derivation/zio-isreloadable.md) | `IsReloadable[A].reloadable(scopedRef)` → reflect proxy forwarding to a ScopedRef; hot-reload; @experimental | 2026-06-07 |
| [distage TraitConstructor](scala-rpc-derivation/distage-traitconstructor.md) | DI auto-implementation of an abstract trait → `Functoid[R]`; Symbol.newClass via reflection shim | 2026-06-07 |

## scala-js

Compiling Scala 3 (including capture-checked dapr4s) to JavaScript/WebAssembly — scala-cli cross-building and cross-publishing, the js.async/js.await + JSPI direct-style story, and capture checking on the JS backend.

| Article | Summary | Updated |
|---------|---------|---------|
| [Cross-Building JVM + Scala.js with Scala CLI](scala-js/scala-js-cross-building-scala-cli.md) | `platform` directive (first = default), `--js`/`--cross`, per-file `target.platform`, `target.platform`-scoped deps files (plain `dep` IS platform-scoped; the leak is `test.dep`-only — `.test.scala` filename workaround), `--exclude` has no inverse, `::` dep syntax, publish --cross (_3 + _sjs1_3), scala-cli >= 1.13.0 floor, cwd-based npm resolution, GH Actions | 2026-06-12 |
| [js.async / js.await, JSPI, and the WebAssembly Backend](scala-js/scala-js-async-jspi-wasm.md) | js.async/js.await semantics (1.19.0+, ES2017+, Scala 3.8+), orphan await + allowOrphanJSAwait, Wasm backend restrictions, JSPI runtime matrix (Node 25+/Chrome 137+), no-intervening-JS-frame rule, rejected Atomics.wait/deasync alternatives, dapr4s's virtual-thread-parking analogue + port field notes (JSImport.Default for CJS, per-request js.async re-entry, AsyncGenerator-from-coroutine recipe) + munit-on-Wasm harness notes (js.async{}.toFuture, raw-Promise vacuous-pass footgun, wasm cleanup-bug wrapper, plain-JS linker wedge on orphan-await test sources, ESM resolution hook, --test-only ineffective on JS, UUID.randomUUID doesn't link) | 2026-06-12 |
| [ScalablyTyped Facades with Scala CLI](scala-js/scalablytyped-with-scala-cli.md) | Converter CLI (1.0.0-beta45) with scala-cli: flag landmines (pin --scala 3.3.6, full --scalajs version, -s es2022, typescript required), @types/* as top-level deps, deterministic npmVersion-digest coordinates from package-lock + converter tuple, ivy2Local zero-config resolution + CI caching, ESM gotchas (deep-module values, CJS default-export shim), MutableBuilder option traits, TS-vs-wire mismatches, the published-POM consumer problem + options | 2026-06-12 |
| [Capture Checking on Scala.js](scala-js/capture-checking-on-scala-js.md) | CC erased in picklerPhases before GenSJSIR; empirical probe (dapr4s nightly + full flag set passes on JS, zero warnings); explicit-nulls × js.native facades; sealed caps.Capability gotcha; munit/upickle _sjs1_3 availability | 2026-06-11 |
