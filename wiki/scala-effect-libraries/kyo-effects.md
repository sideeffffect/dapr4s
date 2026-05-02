# Kyo Effects

> Sources: Flavio Brasil / getkyo, Unknown; Łukasz Biały / VirtusLab, 2026-04-15
> Raw: [Kyo GitHub README](../../raw/scala-effect-libraries/2026-05-01-kyo-github-readme.md); [Comparing Kyo, Gears, Ox](../../raw/scala-effect-libraries/2026-05-01-virtuslab-comparing-kyo-gears-ox.md)
> Updated: 2026-05-01

## Overview

Kyo is an algebraic-effects toolkit for Scala 3 that represents effectful computations via the infix "Pending" type `A < S`, where `A` is the result type and `S` is an intersection type enumerating unhandled effects. Unlike monadic libraries (Cats Effect, ZIO) Kyo does not use `flatMap` chains or wrapper types — pure values and effectful computations live in the same type universe, unified by the `<` abstraction. The library targets JVM, Scala.js, and Scala Native, and is pre-1.0 (RC stage as of early 2026).

## The `<` (Pending) Type

The core abstraction is the opaque type:

```scala
opaque type <[+A, -S]
```

- `A` is the eventual result.
- `S` is the **contravariant** set of pending effects, expressed as a type-level intersection (`&`). Contravariance allows widening: a computation with fewer effects can be used where more are expected.

```scala
val a: Int < Any              = 1          // pure value, no pending effects
val b: Int < Sync             = a          // widened to include Sync
val c: Int < (Sync & Abort[Exception]) = b // widened further
```

Every plain value of type `T` is simultaneously an `T < Any` with an empty effect set. This means there is no "lift" step: values and computations are interchangeable, `map` subsumes `flatMap`, and effect widening is automatic.

## Effect Composition Without Monad Transformers

Kyo's effect set is open and user-extensible — any intersection of effect tags is valid. There is no fixed set of channels (unlike ZIO's `R/E/A` or Cats Effect's fixed `IO`). This gives granular compile-time tracking:

```scala
// An effect that requires both Sync and Abort[IOException]
val computation: Int < (Sync & Scope & Abort[IOException]) = ???
```

The compiler enforces that all listed effects are eventually handled before `.eval` is called. Handling is done with `run*` methods:

```scala
val result: Result[Exception, Int] = Abort.run(Env.run(10)(computation)).eval
```

The `handle` combinator chains these without deep nesting:

```scala
val result: Result[String, Int] =
  computation
    .handle(Abort.run(_))
    .handle(Env.run(10))
    .eval
```

Order of handling matters: it determines the nesting of result types and which effect "wins" on short-circuit.

## Built-in Effects

Key effects in `kyo-prelude` (pure, no IO):
- `Abort[E]` — typed errors (like `Either[E, _]`)
- `Env[A]` — dependency injection (like `Reader`)
- `Var[S]` — mutable state
- `Emit[V]` — streaming values
- `Choice` — non-determinism

Key effects in `kyo-core` (may perform IO):
- `Sync` — synchronous side effects
- `Async` — asynchronous / fiber-based concurrency
- `Scope` — resource lifecycle / bracket

## Direct Syntax

`kyo-direct` provides a macro-based `direct { ... }` block with `.now` / `.later` operators, removing the need to write explicit `map` chains:

```scala
val a: String < (Abort[Exception] & Sync) =
    direct {
        val b = Sync.defer("hello").now
        val c = Abort.get(Right("world")).now
        b + " " + c
    }
```

Effectful hygiene is enforced: any `< T` value inside a `direct` block must be explicitly sequenced with `.now` or captured with `.later`. This is implemented via the [dotty-cps-async](https://github.com/rssh/dotty-cps-async) library.

## Recommended Compiler Flags

Kyo's opaque-type encoding means accidentally discarded computations silently do nothing. These flags catch the most common mistakes:

```scala
scalacOptions ++= Seq(
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error",
  "-language:strictEquality"
)
```

## Performance Model

Kyo uses aggressive inlining and Scala 3 opaque types to minimize allocations. Pure values are `T < Any` at zero cost. The scheduler (`kyo-scheduler`) is an adaptive work-stealing pool designed as a global singleton to avoid the CPU-throttling issues of multiple thread pools.

## Platform and Ecosystem

- **Cross-platform**: JVM, Scala.js, Scala Native for core modules
- **Integrations**: sttp, Tapir, Caliban, ZIO (bidirectional), Cats Effect (bidirectional), Reactive Streams, OpenTelemetry
- **Testing**: `KyoSpecDefault` via `kyo-zio-test`
- **IDE**: IntelliJ support is partial due to advanced Scala 3 features; Metals + SBT BSP recommended

## Comparison with Other Approaches

From the VirtusLab comparison:
- Kyo provides the **most granular effect tracking** of the three (Kyo/Gears/Ox)
- The `A < S` syntax is novel but learnable
- Pre-1.0 maturity requires accepting some API churn
- Monadic at heart (despite direct-style sugar), unlike Ox/Gears which are truly direct-style

## Relevance to DAPR Wrappers

Kyo's open effect set makes it natural to model each DAPR building block as a distinct effect type:
- `StateStore` as `Env[StateStore.Client]` or a custom `DaprState` effect
- `PubSub` as `Emit[CloudEvent]` combined with `Async`
- Errors via `Abort[DaprException]`

The `Scope` effect maps cleanly to resource lifecycle (gRPC channels, HTTP clients). The cross-platform support is a significant advantage for testing outside the JVM.

## See Also

- [Ox Structured Concurrency](ox-structured-concurrency.md)
- [Effekt Capability Passing](effekt-capability-passing.md)
- [Scala Effect Libraries Comparison](scala-effect-libraries-comparison.md)
- [Effect Systems Overview](../effect-systems/effect-systems-overview.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
