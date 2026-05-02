# Kyo GitHub README

> Source: https://github.com/getkyo/kyo
> Collected: 2026-05-01
> Published: Unknown

### Please visit https://getkyo.io for an indexed version of this documentation.

<img src="https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png" width="200" alt="Kyo">

## Introduction

Kyo is a powerful toolkit for Scala development, providing a rich standard library for development across Native, JVM, and JavaScript platforms. Kyo introduces a novel approach based on algebraic effects to deliver straightforward APIs in the pure Functional Programming paradigm.

Kyo achieves this without requiring a pretext of concepts from category theory, and avoiding cryptic operators. This results in a development experience that is both intuitive and robust.

Its effect system builds on a simple yet powerful abstraction: algebraic effects with modular handlers. Unlike systems that rely on a fixed set of effect channels, typically limited to error and environment, Kyo allows developers to define and compose an open set of effects tailored to their specific needs. This enables more precise and granular control over computational context, without unnecessary complexity.

## Getting Started

Kyo is structured as a monorepo, published to Maven Central:

### Core Libraries

| Module          | JVM | JS  | Native | Description                                                   |
| --------------- | --- | --- | ------ | ------------------------------------------------------------- |
| kyo-data        | ✅   | ✅   | ✅      | Efficient `Maybe`, `Result`, `Duration`, and other data types |
| kyo-kernel      | ✅   | ✅   | ✅      | Core algebraic effects engine and type-level effect tracking  |
| kyo-prelude     | ✅   | ✅   | ✅      | Pure effects: `Abort`, `Env`, `Var`, `Emit`, `Choice`, etc.   |
| kyo-parse       | ✅   | ✅   | ✅      | Effects for parsing                                           |
| kyo-core        | ✅   | ✅   | ✅      | Side-effectful computations: `Sync`, `Async`, `Scope`, etc.   |
| kyo-direct      | ✅   | ✅   | ✅      | Direct-style syntax using `.await` and control flow           |
| kyo-combinators | ✅   | ✅   | ✅      | ZIO-like effect combinators and utility methods               |
| kyo-actor       | ✅   | ✅   | ✅      | Type-safe actor system with supervision and messaging         |
| kyo-stm         | ✅   | ✅   | ✅      | Software transactional memory for managing state              |
| kyo-offheap     | ✅   | ❌   | ✅      | Direct memory allocation and off-heap data structures         |

### Integrations

| Module               | JVM | JS  | Native | Description                                                          |
| -------------------- | --- | --- | ------ | -------------------------------------------------------------------- |
| kyo-sttp             | ✅   | ✅   | ✅      | HTTP client using Sttp with automatic effect management              |
| kyo-tapir            | ✅   | ❌   | ❌      | HTTP server endpoints using Tapir with Netty backend                 |
| kyo-caliban          | ✅   | ❌   | ❌      | GraphQL server using Caliban with schema derivation                  |
| kyo-zio              | ✅   | ✅   | ❌      | Bidirectional ZIO interop with support for ZIO, ZLayer, and ZStream  |
| kyo-cats             | ✅   | ✅   | ❌      | Bidirectional Cats IO interop with support for Sync, Async and Abort |
| kyo-stats-otlp       | ✅   | ✅   | ✅      | OpenTelemetry integration for metrics and tracing export             |
| kyo-reactive-streams | ✅   | ❌   | ❌      | Bidirectional Reactive Streams interop implementation                |
| kyo-aeron            | ✅   | ❌   | ❌      | High-performance messaging using Aeron transport                     |

### Scheduler

The scheduler modules are designed to be used independently, without requiring the Kyo effect system. Also compiled with support for Scala 2.

| Module                | JVM | JS  | Native | Description                                                 |
| --------------------- | --- | --- | ------ | ----------------------------------------------------------- |
| kyo-scheduler         | ✅   | ✅   | ✅      | Adaptive work-stealing scheduler with automatic parallelism |
| kyo-scheduler-cats    | ✅   | ❌   | ❌      | Drop-in Cats Effect `IORuntime` replacement                 |
| kyo-scheduler-zio     | ✅   | ❌   | ❌      | ZIO `Runtime` implementation for better ZIO performance     |

## Recommended Compiler Flags

We strongly recommend enabling these Scala compiler flags when working with Kyo:

1. `-Wvalue-discard`: Warns when non-Unit expression results are unused.
2. `-Wnonunit-statement`: Warns when non-Unit expressions are used in statement position.
3. `-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error`: Elevates the warnings from the previous flags to compilation errors.
4. `-language:strictEquality`: Enforces type-safe equality comparisons.

These flags catch three common issues:
1. **A pure expression does nothing in statement position** — a Kyo computation is being discarded and will never execute.
2. **Unused/Discarded non-Unit value** — a computation is passed to a method that can't handle all its required effects.
3. **Values cannot be compared with == or !=** — type-safe equality violations.

## The "Pending" type: `<`

In Kyo, computations are expressed via the infix type `<`, known as "Pending". It takes two type parameters:

```scala
opaque type <[+A, -S]
```

1. `A` - The type of the expected output.
2. `S` - The pending effects that need to be handled. Effects are represented by an unordered type-level set via a type intersection.

```scala
import kyo.*

// 'Int' pending 'Abort[Absent]'
Int < Abort[Absent]

// 'String' pending 'Abort[Absent]' and 'Sync'
String < (Abort[Absent] & Sync)
```

Any type `T` is automatically considered to be of type `T < Any`, where `Any` denotes an empty set of pending effects. This streamlines code by removing the necessity to differentiate between pure values and computations.

```scala
import kyo.*

// An 'Int' is also an 'Int < Any'
val a: Int < Any = 1

// Since there are no pending effects, the computation can produce a pure value
val b: Int = a.eval
```

This unique property removes the need to juggle between `map` and `flatMap`. All values are automatically promoted to a Kyo computation with zero pending effects.

```scala
import kyo.*

def example1(
    a: Int < Sync,
    b: Int < Abort[Exception]
): Int < (Sync & Abort[Exception]) =
    a.flatMap(v => b.map(_ + v))

// Using only `map` is recommended since it functions like `flatMap` due to effect widening
def example2(
    a: Int < Sync,
    b: Int < Abort[Exception]
): Int < (Sync & Abort[Exception]) =
    a.map(v => b.map(_ + v))
```

The `handle` method chains effect handlers without nesting parentheses:

```scala
import kyo.*

val a: Int < (Abort[String] & Env[Int]) =
    for
        v <- Abort.get(Right(42))
        e <- Env.get[Int]
    yield v + e

val b: Result[String, Int] =
    a.handle(Abort.run(_))
     .handle(Env.run(10))
     .eval
```

## Effect Widening

Kyo's set of pending effects is a contravariant type parameter, permitting computations to be widened to encompass a larger set of effects:

```scala
import kyo.*

val a: Int < Any = 1
val b: Int < Sync = a                         // widened to include Sync
val c: Int < (Sync & Abort[Exception]) = b    // widened further
val d: Int < (Sync & Abort[Exception]) = 42   // pure value widened directly
```

## Effect Naming Convention

Effects follow a naming convention for common operations:
- `init*`: Initializes an instance of the container type handled by the effect.
- `get*`: Allows "extraction" of the value of the container type.
- `run*`: Handles a given effect, transforming the result.

```scala
import kyo.*

val a: Int < Abort[Exception] = 42

// Handle the 'Abort' effect; 'Result' is similar to 'Either'
val b: Result[Exception, Int] < Any = Abort.run(a)
val c: Result[Exception, Int] = b.eval
```

The order in which you handle effects influences both the type and value of the result. Since effects are unordered at the type level, the runtime behavior depends on the sequence in which effects are processed:

```scala
import kyo.*

def abortStringFirst(a: Int < (Abort[String] & Abort[Exception])): Result[Exception, Result[String, Int]] =
    Abort.run[Exception](Abort.run[String](a)).eval

def abortExceptionFirst(a: Int < Abort[String | Exception]): Result[String, Result[Exception, Int]] =
    Abort.run[String](Abort.run[Exception](a)).eval

val ex = new Exception
abortStringFirst(Abort.fail("test"))    // Result.Success(Result.Fail("test"))
abortStringFirst(Abort.fail(ex))        // Result.Fail(ex)
abortExceptionFirst(Abort.fail("test")) // Result.Fail("test")
abortExceptionFirst(Abort.fail(ex))     // Result.Success(Result.Fail(ex))
```

## Direct Syntax

Kyo provides direct syntax for a more intuitive way to express computations using `.now` and `.later`:

```scala
import kyo.*

val a: String < (Abort[Exception] & Sync) =
    direct {
        val b: String = Sync.defer("hello").now
        val c: String = Abort.get(Right("world")).now
        b + " " + c
    }
```

The `.now` operator sequences an effect immediately; `.later` preserves an effect without immediate sequencing. The direct syntax enforces effectful hygiene: within a `direct` block, values of the `<` type must be explicitly handled using either `.now` or `.later`.

The `kyo-direct` module is built as an integration with [dotty-cps-async](https://github.com/rssh/dotty-cps-async).

## Defining an App

`KyoApp` offers a structured approach similar to Scala's `App`, handling a suite of default effects (Sync, Async, Scope, Clock, Console, Random, Timer, Aspect):

```scala
import kyo.*

object MyApp extends KyoApp:
    run {
        for
            _            <- Console.printLine(s"Main args: $args")
            currentTime  <- Clock.now
            randomNumber <- Random.nextInt(100)
        yield "example"
    }
end MyApp
```

## Platform Support

Kyo supports JVM, JavaScript (Scala.js), and Scala Native for core modules. The scheduler is also compiled for Scala 2 for broader adoption.

## IDE Support

Kyo uses advanced Scala 3 features that IntelliJ IDEA may not fully support. Recommended: use a Metals-based IDE with SBT BSP server.
