# Capability-Based Effects in Scala 3

> Sources: Nicolas Rinaudo, Unknown; Nicolas Rinaudo, Unknown; Nicolas Rinaudo, Unknown
> Raw: [Effects as Capabilities](../../raw/effect-systems/2026-05-01-effects-as-capabilities-nrinaudo.md); [The right(?) way to work with capabilities](../../raw/effect-systems/2026-05-01-capability-types-nrinaudo.md); [Hands on Capture Checking](../../raw/effect-systems/2026-05-01-hands-on-capture-checking-nrinaudo.md); [Controlling Program Flow with Capabilities](../../raw/effect-systems/2026-05-01-capabilities-flow-nrinaudo.md)
> Updated: 2026-05-01

## Overview

Capability-based effects in Scala 3 use context functions (`A ?=> B`) to express that a computation requires some effect-granting capability. This is a direct-style approach: code looks imperative, but the compiler enforces capability requirements transitively throughout the call graph. Scala 3's capture checking adds a second layer: static verification that capabilities don't escape their intended scope. Together, these features form the foundation of type-safe, direct-style effect programming in Scala 3.

## Core Concepts

### What is a Capability?

A capability is any value whose presence in implicit scope authorizes a particular effect. It is not an effect monad — it is a token that says "the caller is allowed to do X here." Examples:

- `Console` — authorizes I/O to the console
- `Random` — authorizes random number generation
- `FileSystem` — authorizes file operations
- `Label[A]` — authorizes breaking out of an enclosing `boundary`

Capabilities are regular Scala values (traits, classes, or opaque types) passed via the `using`/`?=>` mechanism.

### Declaring Effectful Functions

Two equivalent styles for requiring a capability:

```scala
// Value style (less conventional)
val computation: Rand ?=> Int = ???

// Function style (preferred — more idiomatic Scala)
def computation(using Rand): Int = ???
```

Rinaudo recommends the function style: "the less esoteric the code, the more comfortable it will be to work with."

### Context Functions as Effect Descriptions

`A ?=> B` is a *context function type* — a function from an implicit `A` to `B`. When the compiler sees an expression of type `B` where `A ?=> B` is expected, it automatically lifts it:

```scala
val x: Console ?=> Unit = println("hello")  // auto-lifted
```

This enables composing effects without explicit threading. Multiple capabilities compose by stacking:

```scala
def program(using Console, Random): Unit = ???
// or as a type:
type Program[A] = (Console, Random) ?=> A
```

## Effect Polymorphism

The distinction between `=> A` (by-name) and `Capability ?=> A` (context function) enables effect polymorphism:

- `a: => A` — effectful over *any* capabilities present in the calling scope
- `a: Rand ?=> A` — specifically requires `Rand`, and only `Rand`

This precision matters when writing combinators: a function that takes `=> A` is polymorphic over effects, while one taking `Rand ?=> A` is specific. Rinaudo uses this distinction when defining the `or` combinator to avoid unnecessary capture-checking annotations.

## Capability Handlers

A handler provides a concrete implementation of a capability and runs a program that requires it:

```scala
object LiveConsole extends Console:
  def print(s: String): Unit = System.out.print(s)
  def read(): String = scala.io.StdIn.readLine()

def runWithLiveConsole[A](program: Console ?=> A): A =
  program(using LiveConsole)
```

For testing, swap in a mock:

```scala
def runWithTestConsole[A](program: Console ?=> A): A =
  program(using TestConsole())
```

## Capture Checking: Preventing Capability Escape

Scala 3's experimental capture checking (`-Ycc` flag) adds a second type-level mechanism: tracking what values a function or closure captures, and preventing those values from escaping their valid scope.

### Capture Sets in Types

Every type can carry a capture set indicating what tracked values it holds:

- `A^{a1}` — type `A` that captures value `a1`
- `A^` — shorthand for `A^{cap}` (captures the root capability)
- `A -> B` — pure function (no captures)
- `A => B` — impure function (captures `cap` by default)
- `A ->{x} B` — function capturing only `x`

### The Subset Rule

`T^{c1}` is a subtype of `T^{c2}` when `c1 ⊆ c2`. This means a value with a narrow capture set is more specific and can be used where a wider capture set is expected (without losing safety information).

### Use Cases for Capture Checking

**Resource safety (try-with-resource):**

```scala
def withFile[T](path: Path)(f: OutputStream^{cap} => T): T
```

Declaring the parameter as `OutputStream^{cap} => T` prevents the stream from escaping the `withFile` scope. Attempting to capture and return the stream causes a compile error.

**Secret values:**

```scala
def withSecret[T](secret: SecretToken^{cap} => T): T
```

Secrets cannot be stored in data structures or returned from the handler.

**Capability label safety:** `Label` in `boundary`/`break` is marked `SharedCapability` to prevent it from escaping its enclosing `boundary` call.

### Transitivity

Capture sets track transitively: if `f` captures `x` which captures `resource`, then `f` is considered to capture `resource`. Intermediate values can be dropped from the set for ergonomics; what matters is the root tracked capability.

### Capture Tunneling

When a generic class holds a type parameter `T`, the capture set of `T` does not automatically propagate to the class. Only direct interaction with the captured field causes the class to inherit the capture requirement. This keeps generic code ergonomic.

### Developer Experience

Rinaudo reports that capture checking is "largely transparent" in practice:

> "the vast majority of the code I've written with capture checking was exactly the code I'd have written without it"

The compiler occasionally flags unsafe patterns. The main source of friction is with generic code and variadic parameters (by-name parameters cannot be variadic).

## Practical Patterns

### Composing Capabilities

```scala
trait App:
  def run(using Console, Random, FileSystem): ExitCode
```

### Testing with Fake Handlers

```scala
class TestConsole extends Console:
  val output = mutable.Buffer.empty[String]
  def print(s: String): Unit = output += s
```

### Aggregate Capabilities

Group related primitives behind a higher-level capability:

```scala
trait Logging:
  def info(msg: String)(using Console): Unit
  def warn(msg: String)(using Console): Unit
```

## See Also

- [Direct-Style Effects](direct-style-effects.md)
- [Effect Systems Overview](effect-systems-overview.md)
- [Capabilities for Safe Agents](../capabilities-research/capabilities-for-safe-agents.md)
