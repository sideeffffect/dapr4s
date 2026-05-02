# Comparing Effect Systems in Scala: Kyo, Gears, and Ox

> Source: https://virtuslab.com/blog/scala/comparing-effect-systems-in-scala-kyo-gears-and-ox
> Collected: 2026-05-01
> Published: 2026-04-15

Author: Łukasz Biały, Scala Developer Advocate at VirtusLab. Third installment in a series examining effect systems for concurrent programming in Scala.

## Kyo: Algebraic Effects with Precision

Kyo, created by Flavio Brasil (former contributor to Quill and Twitter's Finagle), represents a younger approach to effect management. The core innovation involves representing effectful computations as `A < S`, where S is an intersection type listing required effects. As the article explains, "an effect of type `Int < (Sync & Scope & Abort[IOException])`" delivers an integer once all capabilities are satisfied.

**Key strengths:**
- Granular effect tracking prevents unsafe combinations (e.g., concurrent mutation with `Async`)
- Aggressive inlining and Scala 3 features minimize allocations
- Elegant `Loop` construct for control flow
- Rich built-in effect library

**Considerations:** Requires compiler flags disabling automatic `Unit` coercion to prevent silent no-ops during refactoring.

## Gears: Direct-Style Effects Through Capabilities

Gears (version 0.2.0), an experimental EPFL project led by Nguyen Pham under Martin Odersky's direction, pursues a fundamentally different path. Rather than encoding effects as monadic values, it uses context functions (`Async ?=> T`) as capabilities — permissions to execute specific effects.

**Distinctive features:**
- Eliminates for-comprehensions; code reads synchronously
- Structural concurrency through scoped capabilities
- Requires Project Loom (virtual threads) for JVM support
- Experimental capture checker prevents leaking scoped capabilities

**Trade-offs:** The capture checker remains brittle during development; complete referential transparency isn't prioritized over accessibility.

## Ox: Localized Effect Constraints

Ox deliberately excludes global effect tracking from signatures. Instead, it enforces "structural constraints locally," allowing unconstrained signatures while preventing runtime mistakes through scoped constructs.

**Philosophy:** The library "doesn't really do" comprehensive effect tracking. Instead, structured concurrency rules and error boundaries (`either:` scopes) guide correct implementations locally.

**Practical advantages:**
- Minimal syntax overhead
- Higher-level `par` construct automatically awaits parallel blocks
- Production-grade maturity (1.0.0+)
- JVM-only (for now), leveraging virtual threads differently than Gears

## Comparative Analysis

| Aspect | Kyo | Gears | Ox |
|--------|-----|-------|-----|
| **Syntax** | Novel but learnable | Plain synchronous + `using Async` | Minimal ceremony |
| **Effect Precision** | Most granular tracking | Context-based capabilities | Invisible by design |
| **Maturity** | Pre-1.0 (RC) | Experimental (0.2.0) | Production (1.0.0+) |
| **Safety Focus** | Compile-time prevention | Capture checking (experimental) | Local scoped rules |

## Key Takeaways

The article identifies a generational divide: monadic approaches (Cats Effect, ZIO, Kyo) trade allocation overhead for compile-time effect information, while direct-style solutions (Ox, Gears) sacrifice signature transparency for simplicity and readability.

Regarding cancellation, modern solutions employ structured concurrency — Kyo propagates channel closure as `Abort[Closed]`, while Ox's `mapPar` automatically interrupts siblings on failure. The legacy `Future` lacked reliable cancellation mechanisms.

Each technology occupies a distinct sweet spot. Monadic solutions require mental model shifts but deliver maximum compile-time guarantees. Direct-style approaches sacrifice signature information for ergonomics, accepting that "def start(): Unit" could perform any operation. Selection depends on team experience, ecosystem requirements, and priority weighting between safety guarantees and code readability.
