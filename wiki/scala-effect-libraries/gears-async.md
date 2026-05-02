# Gears — EPFL Async Library for Scala 3

> Sources: LAMP EPFL, 2026-04; natsukagami (Gears Book), Unknown
> Raw: [Gears EPFL](../../raw/scala-effect-libraries/2026-05-01-gears-epfl.md)
> Updated: 2026-05-01

## Overview

Gears is an experimental asynchronous programming library for Scala 3, developed by the LAMP group at EPFL (Martin Odersky's group). Its design goal is to make concurrent code feel like plain sequential code — direct-style, no monadic wrappers — while preserving the safety guarantees of structured concurrency. Unlike Ox, which targets production JVM use, Gears is explicitly experimental and doubles as a research platform for integrating Scala 3's capture checking into async programming.

Latest release: **v0.3.0** (April 2026). License: Apache-2.0.

## The `Async` Capability

The central concept in Gears is the `Async` trait, which is both a **context** and a **capability**:

```scala
trait Async:
  def await[T](src: Async.Source[T]): T
  def group: CompletionGroup
  def withGroup(group: CompletionGroup): Async
```

Functions that need to suspend pass `Async` as a `using` (context) parameter:

```scala
def fetchData()(using Async): Data = ???
def processItem(item: Item)(using Async): Result = ???
```

This is the idiomatic Gears signature. The `Async` context should never be stored in a class field — that would break the scoping rules that make structured concurrency work.

### Entering the Async World

From synchronous code, use `Async.blocking` (JVM: runs on a virtual thread via Project Loom):

```scala
Async.blocking:
  val result = fetchData()  // suspends, not blocks OS thread
  processItem(result)
```

## Structured Concurrency

### `Async.group` — Scoped Concurrency

The primary tool for structured concurrency. All futures spawned inside the group are automatically cancelled and awaited when the body returns:

```scala
Async.blocking:
  Async.group:
    val f1 = Future { computeA() }
    val f2 = Future { computeB() }
    val a = f1.await
    val b = f2.await
    combine(a, b)
  // both f1 and f2 are done here (completed or cancelled)
```

### `Async.Spawn`

A subtype of `Async` that also allows spawning runnable `Future`s. Only functions that explicitly need to spawn "dangling" futures should take `Spawn`. The common case is: take `Async`, use `Async.group` internally.

## Futures

Gears distinguishes two kinds of `Future[+T]`:

**Active futures** — concurrent computations within the structured concurrency tree. Created with `Future { ... }` or `Task.start`. Require `Async.Spawn` context. Idiomatic rule: **async functions should never return active futures**; they should await internally and return the value directly.

**Passive futures** — represent values arriving from outside Gears (network, file system, interop with `scala.concurrent.Future`). Created with `Future.Promise` or `Future.withResolver`. Functions returning passive futures should NOT take an `Async` parameter.

Multi-future operations:
- `Future.awaitAll(futures)` — wait for all, collect results
- `Future.awaitFirst(futures)` — race, return first result
- `Future.Collector` — streaming result collection

Scala standard library interop:
```scala
import gears.async.ScalaConverters.*
val gearsFuture = scalaFuture.asGears
val scalaFuture2 = gearsFuture.asScala
```

## Channels

Channels enable type-safe communication between concurrent futures:

| Channel type | Buffer |
|---|---|
| `SyncChannel` | None (rendezvous) |
| `BufferedChannel(n)` | Bounded, size `n` |
| `UnboundedChannel` | Unbounded |

Views: `ReadableChannel[T]`, `SendableChannel[T]`.

## Other Primitives

- **`Semaphore`** — bounded concurrency control with `Guard`
- **`Timer` / `TimerEvent`** — delays and periodic events
- **`Cancellable`** — cancellation support
- **`CompletionGroup`** — group futures for bulk cancellation
- **`Resource`** — lifecycle management for async resources
- **`TaskSchedule`** — `Every`, `RepeatUntilSuccess`, `RepeatUntilFailure`, `ExponentialBackoff`, `FibonacciBackoff`

## Relationship to Capture Checking

Gears is designed as a companion to Scala 3's capture checking (`-Ycc` experiment). The connection runs deep:

1. **`Async` as a capability**: In capture-checking terms, `Async` is a capability. A function that suspends must have `Async` in its capture set, making the dependency statically visible. This turns "which functions can suspend" from an invisible runtime property into a compile-time constraint.

2. **Scope enforcement**: Capture checking ensures `Async` cannot escape its lexical scope. A future cannot outlive the `Async` context it was spawned in — the compiler rejects such code rather than detecting it at runtime.

3. **Whole-stdlib annotation**: The standard collections library and the Gears library itself have both been fully annotated with capture information. This serves as a proof-of-concept that ergonomic, zero-overhead capability tracking scales to production-sized codebases.

4. **Research vehicle**: Gears is explicitly the EPFL group's testbed for validating that capture checking is the right language mechanism for making direct-style async safe. The library design is shaped by what capture checking can express.

## How Gears Differs from Ox

Both are direct-style, JVM-only (as of 2026) libraries with structured concurrency. Key differences:

| Dimension | Gears | Ox |
|---|---|---|
| **Origin** | EPFL (Odersky group) | SoftwareMill |
| **Status** | Experimental (0.3.x) | Production (1.0+) |
| **Safety mechanism** | Capture checking (compiler) | Scope discipline (runtime) |
| **Effect visibility** | `Async ?=> T` in signatures | No effect in signatures |
| **Cross-platform** | JVM, Native, Wasm (v0.3+) | JVM only |
| **Ecosystem** | Minimal | Small |
| **Capture checking** | Core design | Optional integration |

Ox's `supervised {}` scope is analogous to `Async.group`, but Ox does not require a capability in function signatures — it relies on structured usage patterns rather than compiler enforcement. Gears trades the experimental nature of capture checking for stronger compile-time guarantees about capability scope.

## Platform Support

- **JVM 21+** — uses virtual threads (Project Loom); `Async.blocking` creates a virtual thread
- **Scala Native 0.5.0+** — delimited continuations on Linux, macOS, BSDs
- **Scala.js + WebAssembly** — added in v0.3.0 (April 2026); note a V8 < 14.2.75 bug causes stack overflows in nested async contexts

## Relevance to the DAPR Wrapper Project

For a safe Dapr wrapper in Scala 3, Gears offers:

1. **Direct-style API**: Dapr operations (state get/set, pubsub, service invocation) map naturally to `using Async` functions. Callers write `dapr.getState(key)` rather than `.flatMap` chains.

2. **Structured lifetime**: Gears' scoped futures ensure the Dapr client does not outlive the `Async` scope — no resource leaks if the wrapper uses `Resource` for client lifecycle.

3. **Capture-checked boundaries**: With `-Ycc`, the wrapper can express "this function talks to Dapr" in its type signature via the `Async` capability, making the dependency on the Dapr runtime statically visible.

4. **Experimental caveat**: Gears is not production-ready. Capture checking is still unstable. For production DAPR use, Ox is the safer choice; for a research-oriented wrapper that leverages CC, Gears is the natural fit.

## See Also

- [Ox Structured Concurrency](ox-structured-concurrency.md)
- [Scala Effect Libraries Comparison](scala-effect-libraries-comparison.md)
- [Capability Classifiers](../scala-capture-checking/capability-classifiers.md)
- [Scoped Capabilities and Polymorphic Effects](../capabilities-research/scoped-capabilities-polymorphic-effects.md)
