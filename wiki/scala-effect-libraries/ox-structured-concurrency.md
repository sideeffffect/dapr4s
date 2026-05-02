# Ox Structured Concurrency

> Sources: SoftwareMill, Unknown; Adam Warski / SoftwareMill, Unknown; Łukasz Biały / VirtusLab, 2026-04-15
> Raw: [Ox GitHub README](../../raw/scala-effect-libraries/2026-05-01-ox-github-readme.md); [Understanding Capture Checking in Scala](../../raw/scala-effect-libraries/2026-05-01-softwaremill-capture-checking.md); [Comparing Kyo, Gears, Ox](../../raw/scala-effect-libraries/2026-05-01-virtuslab-comparing-kyo-gears-ox.md)
> Updated: 2026-05-01

## Overview

Ox is a production-ready (1.0+), JVM-only direct-style library for safe concurrency, streaming, and resiliency in Scala 3. It requires JDK 21+ (Project Loom virtual threads) and deliberately does not perform global effect tracking — safety is enforced locally via structured concurrency scopes rather than in type signatures. Ox's philosophy is that correct programs are easier to write when the rules are localized and the syntax overhead is minimal.

## Design Philosophy: Local Safety Without Effect Types

Unlike Kyo (which tracks effects as type-level intersections) or Gears (which uses context functions as capabilities), Ox's approach is pragmatic: function signatures do not carry effect information. Instead, the library enforces correctness through **structured concurrency scopes**:

- Forks (threads) can only be created inside a `supervised { }` block.
- The block does not return until all forks have completed (or been interrupted).
- Failures in any fork trigger interruption of all sibling forks.

This means you cannot accidentally spawn a thread that outlives its scope, but you do not see this guarantee in the function signature. The VirtusLab comparison notes that "Ox doesn't really do" comprehensive effect tracking — by design.

## Structured Concurrency

The `supervised` scope is the backbone of Ox's safety model:

```scala
supervised {
  val f1 = fork { sleep(2.seconds); 1 }
  val f2 = fork { sleep(1.second); 2 }
  (f1.join(), f2.join())
}
// Both forks are done before this line is reached
```

Error propagation follows the "let it crash" principle — if any `forkUser` throws, all others are interrupted:

```scala
supervised {
  forkUser { sleep(1.second); println("Hello!") }
  forkUser { sleep(500.millis); throw RuntimeException("boom!") }
}
// The first fork is interrupted; the scope re-throws after all forks finish
```

## High-Level Concurrency Operators

Ox provides concise wrappers for common patterns:

```scala
// Run two computations in parallel, wait for both
val (a, b): (Int, String) = par(computation1, computation2)

// Timeout: interrupts the computation if it takes too long
val result: Either[TimeoutException, Int] =
  timeout(1.second)(slowComputation).catching[TimeoutException]

// Race: return the first to complete, interrupt the other
val winner: Int = raceSuccess(fast, slow)
```

`par` automatically awaits all branches. `timeout` and `raceSuccess` guarantee the losing branch is interrupted and awaited before returning — no "ghost" threads.

## Error Handling: `either` Scope

Ox uses a `boundary`/`break`-style approach for typed error propagation without exceptions. The `either` scope unwraps `Either` values and accumulates error types in a union:

```scala
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

`.ok()` short-circuits the `either` block if the value is `Left`, propagating the error. Multiple error types are combined as a union type, preserving full type information without a common supertype.

## Capture Checking and Scope Safety

SoftwareMill's article on capture checking explains how Ox integrates with Scala 3's `-Ycc` experiment. Capture checking can statically prevent concurrency scope leakage — ensuring that a `Fork` or channel cannot escape the `supervised` block that created it:

```scala
def withFile[T](name: String)(op: InputStream^ => T): T
// Attempting withFile("data.txt")(identity) is a compile error:
// the InputStream^ cannot escape
```

Applied to concurrency, this means the compiler can verify that objects referencing a `supervised` scope's capabilities cannot be stored or returned outside that scope. Ox is positioned to benefit from this as the feature matures in Scala 3.

The key type hierarchy under capture checking:
- `JsonParser` (no captures) ⊆ `JsonParser^{in1}` (captures specific thing) ⊆ `JsonParser^` (unknown captures)
- Pure functions: `A -> B` (no captures) vs regular functions `A => B` (may capture unknown capabilities)

## Streaming

Ox provides push-based, backpressured streaming (`Flow`) designed for direct style:

```scala
Flow.iterate(0)(_ + 1)
  .filter(_ % 2 == 0)
  .map(_ + 1)
  .mapStateful(0)((state, v) => (state + v, state + v))
  .take(10)
  .runForeach(println)
```

Concurrent streaming with `mapPar`:

```scala
Flow
  .fromInputStream(getResourceAsStream("/list.txt"))
  .linesUtf8
  .mapPar(4)(sendHttpRequest)
  .runDrain()
```

Channels provide Go-style communication between forks within a scope:

```scala
val c = Channel.buffered[String](8)
supervised {
  fork { c.send("Hello,"); c.send("World"); c.done() }
  fork { c.foreach(println) }
}
```

`select` supports multi-channel selection similar to Go's `select` statement.

## Resiliency

Ox bundles resiliency primitives:

```scala
// Exponential backoff with jitter
retry(Schedule.exponentialBackoff(100.millis).maxRetries(4).jitter().maxInterval(5.minutes))(op)

// Fixed-interval repeat
repeat(Schedule.fixedInterval(100.millis))(op)

// Rate limiter
val rl = RateLimiter.fixedWindowWithStartTime(2, 1.second)
rl.runBlocking { /* ... */ }
```

Circuit breakers and bulkheads are also included.

## Actors

Ox includes lightweight typed actors that are scoped within a `supervised` block:

```scala
class Stateful { def increment(delta: Int): Int = ??? }

supervised:
  val ref = Actor.create(new Stateful)
  ref.ask(_.increment(5))  // ref cannot outlive the supervised scope
```

## Application Lifecycle

`OxApp` provides structured shutdown on SIGINT/SIGTERM:

```scala
object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    // structured concurrency scope available here
    ExitCode.Success
```

## Maturity and Ecosystem

- **Version**: 1.0.4 (production-ready)
- **Platform**: JVM only (requires JDK 21+)
- **Integrations**: sttp client, Tapir (both have direct-style variants from SoftwareMill)
- **Related**: [gears](https://github.com/lampepfl/gears) — experimental multi-platform alternative from EPFL

## Limitations (Per Nedelcu Critique)

Alexandru Nedelcu notes that Ox's JVM-only scope is a deliberate platform constraint, abandoning Scala's cross-platform ambitions. It works well with Java 21+ virtual threads but cannot target JavaScript or native environments.

## Relevance to DAPR Wrappers

Ox's strengths for DAPR:
- `supervised` blocks map naturally to request lifecycle (start sidecar calls, await results, clean up)
- `Channel` is a natural fit for DAPR pub/sub consumers
- `either` scope handles DAPR API errors without checked-exception ceremony
- Resiliency primitives (retry, circuit breaker) align with DAPR's own resiliency building block

Key limitation: JVM-only. If the DAPR wrapper needs to run in Scala.js (e.g., for testing or edge deployment), Ox cannot be used.

## See Also

- [Kyo Effects](kyo-effects.md)
- [Scala Effect Libraries Comparison](scala-effect-libraries-comparison.md)
- [Scala Caps Capability](scala-caps-capability.md)
- [Capture Checking Overview](../scala-capture-checking/capture-checking-overview.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
