# Direct-Style Effects

> Sources: Noel Welsh, 2024-04-24; Nicolas Rinaudo, Unknown
> Raw: [Direct-style Effects Explained](../../raw/effect-systems/2026-05-01-direct-style-effects-noel-welsh.md); [Controlling Program Flow with Capabilities](../../raw/effect-systems/2026-05-01-capabilities-flow-nrinaudo.md); [Effects as Capabilities](../../raw/effect-systems/2026-05-01-effects-as-capabilities-nrinaudo.md)
> Updated: 2026-05-01

## Overview

Direct-style effects allow writing effectful code that looks like ordinary imperative code — no `flatMap`, no `for`-comprehensions — while the type system still tracks which effects are present. Scala 3 supports this via two complementary mechanisms: *capability-passing* with context functions (`?=>`) for simple effects, and `boundary`/`break` for control-flow effects that require continuations. Together they offer most of what algebraic effect systems provide in a production Scala environment.

## What Makes a Style "Direct"?

In monadic style, every effectful step must be explicitly chained:

```scala
for
  a <- fetchUser(id)
  b <- fetchOrders(a)
yield b
```

In direct style, the same computation reads like regular code:

```scala
val a = fetchUser(id)
val b = fetchOrders(a)
b
```

The effect tracking is not eliminated — it is moved into the *types* of `fetchUser` and `fetchOrders` and handled by the language machinery rather than by explicit programmer plumbing.

## Capability-Passing Style (Simple Effects)

For effects that don't need to manipulate control flow, Scala 3's context functions suffice. A *capability* is any value (often a trait or class instance) whose presence in scope signals that a particular effect is allowed:

```scala
type Print[A] = Console ?=> A

def greet(name: String): Print[Unit] =
  println(s"Hello, $name")
```

The function `greet` *requires* a `Console` capability. Callers must either provide one or propagate the requirement. The compiler enforces this transitively across the entire call graph.

Key behaviors:
- **Eager application:** Context functions are applied as soon as the required given is in scope.
- **Automatic lifting:** If the compiler expects `A ?=> B` but sees `B`, it wraps automatically.
- **Order independence:** Multiple capabilities can be reordered freely.

This makes capability-passing ergonomic: you write direct-style code and the compiler handles threading capabilities up the stack.

## Control-Flow Effects: `boundary` and `break`

Simple capabilities cannot short-circuit execution. For effects like early exit, error propagation, or structured concurrency, you need *delimited continuations* — a way to "jump" to an enclosing handler.

Scala 3 provides `boundary` and `break` (from `scala.util.boundary`):

```scala
def sequence[A](oas: List[Option[A]]): Option[List[A]] =
  boundary:
    Some(oas.map:
      case Some(a) => a
      case None    => break(Option.empty)
    )
```

`boundary` establishes a prompt. `break` jumps to it, delivering a value. The mechanism is implemented via exceptions internally but is type-safe: `break` requires a `Label[A]` capability in scope, which only exists inside the matching `boundary`.

### Ergonomic Wrappers

The raw `boundary`/`break` API can be wrapped into domain-specific combinators:

```scala
// Option-specific
def option[A](body: Label[Unit] ?=> A): Option[A]

// Either-specific
def either[E, A](body: Label[E] ?=> A): Either[E, A]
```

Extension methods inspired by Rust's `?` operator:

```scala
extension [A](oa: Option[A]) def ? : Label[Unit] ?=> A =
  oa match
    case Some(a) => a
    case None    => break
```

Enabling: `option: oas.map(_.?)`

### Nested Prompts

Multiple boundaries can nest. By default `break` targets the innermost matching label, but you can thread explicit labels to target outer prompts:

```scala
either: fail ?=>
  option:
    if problem then break(s"error")(using fail)  // targets outer Either
    else normalValue
```

## Relationship to Monadic Effects

Welsh notes that monads are mathematically equivalent to delimited continuations. This means:

- Everything expressible with `IO` monad is expressible with direct-style effects
- The ergonomic difference is significant: direct style is accessible to programmers unfamiliar with monads
- The tradeoff is that monadic style makes effects more *visible* at each call site; direct style hides them behind type signatures

## Capture Checking and Safety

Both capability-passing and `boundary`/`break` have a safety concern: what if a capability or `Label` escapes its handler scope and is used later? Scala 3's experimental **capture checking** (`-Ycc`) addresses this by tracking which values a closure captures and preventing unsafe escapes.

Rinaudo notes that `Label` is marked `SharedCapability` to prevent it from escaping, but current implementations require some unsafe assertions internally. As capture checking matures, these safety properties will be enforced more precisely.

## Comparison with Monadic Effects

| Aspect | Monadic (IO/ZIO/Cats Effect) | Direct-style (capabilities + boundary) |
|---|---|---|
| Syntax | `for`-comprehensions / `flatMap` | Plain Scala expressions |
| Learning curve | Higher (monadic thinking required) | Lower (familiar imperative style) |
| Effect tracking | Explicit in every return type | In types, invisible at call site |
| Control flow | Monadic combinators | `boundary`/`break`, native loops |
| Ecosystem maturity | Very mature | Emerging in Scala 3 |
| Capture safety | Manual / convention | Type-enforced with `-Ycc` |

## See Also

- [Effect Systems Overview](effect-systems-overview.md)
- [Capability-Based Effects](capability-based-effects.md)
- [Capabilities for Safe Agents](../capabilities-research/capabilities-for-safe-agents.md)
