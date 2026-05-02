# Ox GitHub README

> Source: https://github.com/softwaremill/ox
> Collected: 2026-05-01
> Published: Unknown

Safe direct-style streaming, concurrency and resiliency for Scala on the JVM. Requires JDK 21+ & Scala 3. Ox covers the following areas:

* streaming: push-based backpressured streaming designed for direct-style, with a rich set of stream transformations, flexible stream source & sink definitions and reactive streams integration
* error management: retries, timeouts, a safe approach to error propagation, safe resource management
* concurrency: high-level concurrency operators, developer-friendly structured concurrency, safe low-level primitives, communication between concurrently running computations
* scheduling & timers
* resiliency: circuit breakers, bulkheads, rate limiters, backpressure

Ox enables writing simple, expression-oriented code in functional style. The syntax overhead is kept to a minimum, preserving developer-friendly stack traces, and without compromising performance.

```scala
"com.softwaremill.ox" %% "core" % "1.0.4"
```

Documentation: https://ox.softwaremill.com

## Tour of ox

Run two computations in parallel:

```scala
def computation1: Int = { sleep(2.seconds); 1 }
def computation2: String = { sleep(1.second); "2" }
val result1: (Int, String) = par(computation1, computation2)
// (1, "2")
```

Timeout a computation:

```scala
def computation3: Int = { sleep(2.seconds); 1 }
val result2: Either[TimeoutException, Int] = timeout(1.second)(computation3).catching[TimeoutException]
// `timeout` only completes once the losing branch is interrupted & done
```

Race two computations:

```scala
def computation4: Int = { sleep(2.seconds); 1 }
def computation5: Int = { sleep(1.second); 2 }
val result3: Int = raceSuccess(computation4, computation5)
// the losing branch is interrupted & awaited before returning a result
```

Structured concurrency & supervision:

```scala
// equivalent of par
supervised {
  val f1 = fork { sleep(2.seconds); 1 }
  val f2 = fork { sleep(1.second); 2 }
  (f1.join(), f2.join())
}
```

Error handling within a structured concurrency scope:

```scala
supervised {
  forkUser:
    sleep(1.second)
    println("Hello!")

  forkUser:
    sleep(500.millis)
    throw new RuntimeException("boom!")
}
// on exception, all other forks are interrupted ("let it crash")
// the scope ends & re-throws only when all forks complete (no "leftovers")
```

Retry a computation:

```scala
retry(Schedule.exponentialBackoff(100.millis).maxRetries(4)
  .jitter().maxInterval(5.minutes))(computation)
```

Repeat a computation:

```scala
repeat(Schedule.fixedInterval(100.millis))(computation)
```

Rate limit computations:

```scala
supervised:
  val rateLimiter = RateLimiter.fixedWindowWithStartTime(2, 1.second)
  rateLimiter.runBlocking({ /* ... */ })
```

Allocate a resource in a scope:

```scala
supervised {
  val writer = useCloseableInScope(new java.io.PrintWriter("test.txt"))
  // ... use writer ...
} // writer is closed when the scope ends (successfully or with an error)
```

Create an app which shuts down cleanly when interrupted with SIGINT/SIGTERM:

```scala
object MyApp extends OxApp:
  def run(args: Vector[String])(using Ox): ExitCode =
    // ... your app's code ...
    // might use fork {} to create top-level background threads
    ExitCode.Success
```

Simple type-safe actors:

```scala
class Stateful { def increment(delta: Int): Int = ??? }

supervised:
  val ref = Actor.create(new Stateful)
  // ref can be shared across forks, but only within the concurrency scope
  ref.ask(_.increment(5))
```

Create a simple flow & transform using a functional API:

```scala
Flow.iterate(0)(_ + 1) // natural numbers
  .filter(_ % 2 == 0)
  .map(_ + 1)
  .intersperse(5)
  .mapStateful(0) { (state, value) =>
    val newState = state + value
    (newState, newState)
  }
  .take(10)
  .runForeach(n => println(n.toString))
```

Create flows which perform I/O and manage concurrency:

```scala
Flow
  .fromInputStream(this.getClass().getResourceAsStream("/list.txt"))
  .linesUtf8
  .mapPar(4)(sendHttpRequest)
  .runDrain()
```

Use completable high-performance channels for inter-fork communication within concurrency scopes:

```scala
val c = Channel.buffered[String](8)
c.send("Hello,")
c.send("World")
c.done()
```

Select from Go-like channels:

```scala
val c = Channel.rendezvous[Int]
val d = Channel.rendezvous[Int]
select(c.sendClause(10), d.receiveClause)
```

Unwrap eithers and combine errors in a union type:

```scala
val v1: Either[Int, String] = ???
val v2: Either[Long, String] = ???

val result: Either[Int | Long, String] = either:
  v1.ok() ++ v2.ok()
```

## Other Projects

The wider goal of direct-style Scala is enabling teams to deliver working software quickly and with confidence. Other SoftwareMill projects including sttp client and Tapir also include integrations directly tailored towards direct style.

Also check out the [gears](https://github.com/lampepfl/gears) project, an experimental multi-platform library covering direct-style Scala.

## Copyright

Copyright (C) 2023-2025 SoftwareMill https://softwaremill.com
