# Scala Effect Libraries Comparison

> Sources: Łukasz Biały / VirtusLab, 2026-04-15; Flavio Brasil / getkyo, Unknown; SoftwareMill, Unknown; Alexandru Nedelcu / alexn.org, 2025-08-29
> Raw: [Comparing Kyo, Gears, Ox](../../raw/scala-effect-libraries/2026-05-01-virtuslab-comparing-kyo-gears-ox.md); [Kyo GitHub README](../../raw/scala-effect-libraries/2026-05-01-kyo-github-readme.md); [Ox GitHub README](../../raw/scala-effect-libraries/2026-05-01-ox-github-readme.md); [Scala's Gamble with Direct Style](../../raw/scala-effect-libraries/2026-05-01-alexn-scala-gamble-direct-style.md)
> Updated: 2026-05-01

## Overview

The Scala effect library landscape as of 2026 divides into two generations: **monadic** (Cats Effect, ZIO, Kyo) and **direct-style** (Ox, Gears). A third research lineage — **capability-passing** (Effekt) — predates both and now manifests in Scala 3's native capture-checking experiment. This article synthesizes the design differences, trade-offs, and relevance to building a DAPR wrapper library.

## Design Approach by Library

### Cats Effect (`cats-effect`)

**Approach**: Monad-based, `IO[A]` wrapper. Effect tracking via `F[_]` type class abstraction (tagless final pattern).

- Effects declared by constraining `F[_]` with type classes (`Concurrent[F]`, `Temporal[F]`).
- Full ecosystem: dozens of libraries; the de facto standard for FP Scala.
- Fibers for concurrency; `Resource` for lifecycle.
- Cross-platform: JVM, Scala.js, Scala Native.
- **Compile-time safety**: high (via type classes), but not granular — you cannot easily express "this function needs Async but not Concurrent".

### ZIO (`zio`)

**Approach**: Monad-based, `ZIO[R, E, A]` (environment, error, result). Strongly opinionated.

- `R` is an environment intersection type for dependency injection; `E` is typed errors.
- Rich standard library (streams, STM, actors, metrics).
- `ZLayer` for DI graph construction.
- Cross-platform: JVM, Scala.js (partial).
- **Compile-time safety**: high and granular — `R` encodes capability requirements explicitly.
- Closed effect set (cannot add new effect types beyond what ZIO provides).

### Kyo (`kyo`)

**Approach**: Algebraic effects via opaque `A < S` type. Open effect set via intersection types.

- `S` is an intersection of effect tags — fully extensible with user-defined effects.
- Cross-platform: JVM, Scala.js, Scala Native (core modules).
- Monadic at heart but with direct-style sugar (`direct { ... }.now`).
- **Compile-time safety**: very granular — the exact effect set is visible in every type. Handler ordering affects result types.
- Pre-1.0 (RC stage, API may change).
- Requires specific compiler flags to avoid silent computation discard.

### Ox (`ox`)

**Approach**: Direct-style, no effect types in signatures. Safety enforced via structured concurrency scopes.

- Built on JVM virtual threads (Project Loom); requires JDK 21+.
- JVM-only.
- `supervised {}` scopes prevent fork leakage; `either:` scopes handle typed errors.
- **Compile-time safety**: low in signatures, high in structure. You cannot easily tell from a function type what effects it has.
- Production-ready (1.0+).
- Optionally integrates with Scala 3 capture checking for stronger static guarantees.

### Gears (EPFL, experimental)

**Approach**: Direct-style via context functions (`Async ?=> T`). Capture checking for scope safety.

- Experimental (`0.2.0`); Martin Odersky's group at EPFL.
- Requires Project Loom; JVM-only for now.
- Uses Scala 3's capture checker (brittle during development).
- **Compile-time safety**: moderate — capabilities in signatures, but capture checker is not yet stable.

### Effekt (capability-passing, research)

**Approach**: Capabilities passed as function parameters; lexical effect handlers.

- Scala Effekt library: discontinued (Scala 2 only, last 0.4-SNAPSHOT).
- Effekt language: standalone, research-level, not production.
- **Compile-time safety**: strong — effects visible in types (`Int / { raise }`), lexically scoped.
- Not suitable for production Scala use today, but the research informs Scala 3's CC direction.

## Feature Comparison Matrix

| Dimension | Cats Effect | ZIO | Kyo | Ox | Gears | Effekt |
|-----------|------------|-----|-----|----|-------|--------|
| **Style** | Monadic | Monadic | Monadic + direct sugar | Direct | Direct | Capability-passing |
| **Effect types in signatures** | Via type classes | `ZIO[R,E,A]` | `A < S` (full set) | No | `Async ?=> T` | `A / {effects}` |
| **Granularity of tracking** | Type-class level | `R` intersection | Per-effect set | None | Capability-based | Per-effect |
| **Open effect set** | Yes (via type classes) | No | Yes | N/A | Partial | Yes |
| **Cross-platform** | JVM/JS/Native | JVM/JS | JVM/JS/Native | JVM only | JVM only | Research only |
| **Structured concurrency** | Yes (fibers) | Yes (fibers) | Yes (Async/Scope) | Yes (supervised) | Yes (scoped) | N/A |
| **Capture checking integration** | No | No | Planned | Partial | Core | Conceptual basis |
| **Maturity** | Production | Production | RC (pre-1.0) | Production | Experimental | Research |
| **Ecosystem** | Largest | Large | Growing | Small | Minimal | Academic |

## The Monadic / Direct-Style Divide

The VirtusLab analysis identifies this as the key generational split:

> "Monadic solutions require mental model shifts but deliver maximum compile-time guarantees. Direct-style approaches sacrifice signature information for ergonomics."

- **Monadic** (Cats, ZIO, Kyo): effect information in types → reasoning about composition, refactoring safety, but `flatMap`/`for` ceremony or macro-based desugaring.
- **Direct-style** (Ox, Gears): plain function calls → familiar, readable, easy to onboard, but function types don't tell you what effects occur.

Nedelcu's critique adds a third dimension: **cross-platform ambition**. Ox and Gears both sacrifice Scala.js/Native targets for direct-style JVM simplicity.

## Capture Checking as the Unifying Thread

Scala 3's `scala.caps.Capability` and `-Ycc` experiment are the language-native bridge between the two styles:

- **Ox** uses scoped values; capture checking can make those scopes compiler-verified.
- **Gears** uses capture checking as its primary safety mechanism.
- **Effekt** pioneered the capability-passing model that CC implements.
- **Kyo** tracks effects at the type level without CC; future versions may integrate CC for stronger guarantees.

As capture checking stabilizes, the distinction between "effects in types" and "effects tracked by the compiler" may blur: `^` annotations on context functions become a lightweight effect system that works with direct-style code.

## Relevance to Building a DAPR Wrapper

A DAPR wrapper library ("scala-safe-dapr") exposes DAPR building blocks as Scala capabilities. Design considerations:

### Effect modeling

Each DAPR building block is a natural effect/capability:
- State store → `StateStore extends Capability` or `Env[StateStore]`
- Pub/Sub → `PubSub extends Capability` or `Emit[CloudEvent]`
- Service invocation → `ServiceInvocation extends Capability`
- Actor runtime → `ActorRuntime extends Capability`

With **Kyo**, each becomes an effect tag in the `< S` intersection. With **Ox**, capabilities are passed as context parameters and scoped to `supervised` blocks. With **Scala 3 capture checking**, they are typed capabilities with `^` annotations.

### Which library to build on

| Use case | Recommended choice |
|----------|-------------------|
| Maximum compile-time safety, granular effect tracking | Kyo (once 1.0 stable) |
| Production-ready, JVM-only, minimal friction | Ox |
| Zero external dependencies, native Scala 3 | scala.caps.Capability + context functions |
| Cross-platform (JVM + JS + Native) | Kyo or Cats Effect |

For **scala-safe-dapr** specifically:
- The project's goal is "safe Scala capabilities" — this aligns with the `scala.caps.Capability` native approach.
- Using `scala.caps.Capability` directly keeps the library dependency-free and forward-compatible with CC stabilization.
- Ox can be offered as an optional integration for JVM-only structured concurrency patterns.
- Kyo integration makes sense for users who want algebraic effect composition across DAPR and business logic.

### Error handling

All approaches handle typed errors, but differently:
- Kyo: `Abort[DaprException]` in the effect set
- Ox: `either:` scope with union error types
- Cats/ZIO: `IO[Either[E, A]]` or `ZIO[R, E, A]`

DAPR errors are inherently typed (each building block has its own error categories), making Kyo's `Abort[E]` or ZIO's `E` parameter a natural fit.

### Resource lifecycle

DAPR clients (gRPC channels, HTTP connections) need controlled lifecycle:
- Kyo: `Scope` effect
- Ox: `useCloseableInScope` inside `supervised`
- Scala 3 CC: `scala.caps.Capability` with `withResource`-style functions

## See Also

- [Kyo Effects](kyo-effects.md)
- [Ox Structured Concurrency](ox-structured-concurrency.md)
- [Gears Async](gears-async.md)
- [Effekt Capability Passing](effekt-capability-passing.md)
- [Scala Caps Capability](scala-caps-capability.md)
- [Effect Systems Overview](../effect-systems/effect-systems-overview.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
- [Capabilities for Safe Agents](../capabilities-research/capabilities-for-safe-agents.md)
