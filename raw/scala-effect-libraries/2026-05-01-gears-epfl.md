# Gears — EPFL Experimental Async Library for Scala 3

> Source: https://github.com/lampepfl/gears; https://lampepfl.github.io/gears; https://natsukagami.github.io/gears-book
> Collected: 2026-05-01
> Published: Unknown

## GitHub README (lampepfl/gears)

An Experimental Asynchronous Programming Library for Scala 3. It aims to be:

- **Simple**: enables direct-style programming (suspending with `.await`, calling Async-functions directly) and comes with few simple concepts.
- **Structured**: allows an idiomatic way of structuring concurrent programs minimizing computation leaking (*structured concurrency*), while providing a toolbox for dealing with external, unstructured events.
- **Cross-platform**: Works on JVM >= 21, Scala Native and Scala.js with WAsm support.

### Getting Started

The [Gears Book](https://natsukagami.github.io/gears-book) is a great way to get started with programming using Gears. It provides a tutorial, as well as a guided walkthrough of all concepts available within Gears.

### Adding `gears` to your dependencies

With sbt:
```scala
libraryDependencies += "ch.epfl.lamp" %%% "gears" % "<version>"
```

With mill:
```scala
def ivyDeps = Agg(
  ivy"ch.epfl.lamp::gears:<version>"
)
```

With `scala` (since 3.5.0) or `scala-cli`:
```scala
//> using dep "ch.epfl.lamp::gears:<version>"
```

Latest release: v0.3.0 (April 2026) — adds Scala WASM support.

### Related Projects

- [ox](https://github.com/softwaremill/ox): Safe direct-style concurrency and resiliency for Scala on the JVM.

---

## Core API (from shared/src/main/scala/async/Async.scala)

### `Async` trait

The central capability/context of the library:

```scala
trait Async private[async] (using val support: AsyncSupport, val scheduler: support.Scheduler):
  def await[T](src: Async.Source[T]): T
  def group: CompletionGroup
  def withGroup(group: CompletionGroup): Async
```

Idiomatic usage — passed as `using` parameter:

```scala
def function()(using Async): T = ???
```

Not recommended to store `Async` in a class field as it complicates scoping rules.

### `Async.Spawn`

A special subtype of `Async` capable of spawning runnable `Future`s:

```scala
opaque type Spawn <: Async = Async
```

Most functions should NOT take `Spawn` unless they explicitly want "dangling" runnable futures. Instead take `Async` and use `Async.group`.

### `Async.blocking`

Introduces an `Async` context from synchronous code (JVM: uses virtual threads):

```scala
inline def blocking[T](using fromSync: FromSync.Blocking)(body: Async.Spawn ?=> T): T
```

### `Async.group`

Scoped concurrency: all spawned futures are cancelled and waited for when body returns:

```scala
def group[T](body: Async.Spawn ?=> T)(using Async): T
```

---

## Future Types (from shared/src/main/scala/async/futures.scala)

```scala
trait Future[+T] extends Async.OriginalSource[Try[T]], Cancellable
```

Two kinds:

**Active futures** — spawned with `Future.apply` or `Task.start`. Require `Async.Spawn` context. Represent concurrent computations within Gears' structured concurrency tree. Idiomatic Gears code should **never** return active futures; async functions should return values directly.

**Passive futures** — created by `Future.Promise` or `Future.withResolver`. Represent values arriving from outside Gears' structured concurrency tree (network, file system, external concurrency systems). Libraries may return these; such functions should NOT take an `Async` parameter.

Multi-future combinators:
- `Future.awaitAll` — wait for all
- `Future.awaitFirst` — wait for first
- `Future.Collector` — collect results progressively

Scala interop:
- `ScalaConverters.asGears` — convert `scala.concurrent.Future` to Gears future
- `ScalaConverters.asScala` — convert Gears future to `scala.concurrent.Future`

---

## Channel Types (from shared/src/main/scala/async/channels.scala)

- `BufferedChannel` — bounded buffer
- `UnboundedChannel` — unbounded buffer
- `SyncChannel` — rendezvous (no buffer)
- `ReadableChannel` — read-only view
- `SendableChannel` — write-only view

---

## Task and Scheduling

```scala
// TaskSchedule variants:
TaskSchedule.Every(duration)
TaskSchedule.RepeatUntilSuccess
TaskSchedule.RepeatUntilFailure
TaskSchedule.ExponentialBackoff(...)
TaskSchedule.FibonacciBackoff(...)
```

---

## Additional Primitives

- `Semaphore` — synchronization with `Guard`
- `Timer` / `TimerEvent` — scheduling and delays
- `Cancellable` — cancellation support
- `CompletionGroup` — groups related async operations for bulk cancellation
- `Resource` — lifecycle management for async resources
- `Listener` — event notification mechanism

---

## Gears Book — Core Concepts Summary

From https://natsukagami.github.io/gears-book (redirects to http://blog.nkagami.me/gears-book):

### Basic Concepts covered:
- Async functions and direct-style programming
- Futures for concurrent operations
- Structured concurrency with groups and scoping
- Channels for inter-future communication
- Supervision mechanisms (retries and timeouts)

### Unstructured Concurrency:
- Sources as primitives
- Passive Futures and Promises
- Select and Race operations
- Locking and cancellation patterns

### Programming Patterns:
- Structured concurrency approaches
- Avoiding direct Future returns
- Blocking operations guidance
- Referential transparency with async blocks
- Async lambda parameters

---

## Platform Support

- JVM >= 21 (uses virtual threads / Project Loom)
- Scala Native 0.5.0+ (delimited continuations on Linux, macOS, BSDs)
- Scala.js with WebAssembly (v0.3.0+)

Note: On V8 < 14.2.75 (Node.js 24/25), a bug causes stack overflows in nested async contexts. Recommended: wait for a later release, use Node.js 23, or use Deno/Bun/Firefox as WAsm runtime.

---

## Relationship to Capture Checking

From the Gears homepage and docs talks:

- Martin Odersky's group at EPFL developed Gears as a companion to Scala 3's capture checking experiment.
- The `Async` context is a **capability** in the capture-checking sense: functions that suspend must capture `Async`, making the dependency visible to the type system.
- The standard collections library and the Gears async library have both been annotated with capture information, demonstrating feasibility of ergonomic CC across large codebases.
- Capture checking ensures that `Async` capabilities cannot escape their lexical scope, providing compiler-enforced structured concurrency.

---

## Repository Metadata

- Organization: LAMP, EPFL
- License: Apache-2.0
- Latest release: v0.3.0 (April 2026)
- Stars: 294, Forks: 31
- Primary language: Scala (99.2%)
