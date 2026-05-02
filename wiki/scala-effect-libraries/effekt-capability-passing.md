# Effekt Capability-Passing Style

> Sources: Jonathan Brachthäuser, Philipp Schuster, Klaus Ostermann / Cambridge UP, 2020; Effekt Language Team / effekt-lang.org, Unknown
> Raw: [Effekt Capability-Passing Paper](../../raw/scala-effect-libraries/2026-05-01-effekt-capability-passing-paper.md); [Effekt Language Overview](../../raw/scala-effect-libraries/2026-05-01-effekt-lang-overview.md); [Scala Effekt README](../../raw/scala-effect-libraries/2026-05-01-scala-effekt-readme.md)
> Updated: 2026-05-01

## Overview

Effekt is the name of both a discontinued Scala library and a continuing standalone programming language, both arising from the same research program. The core contribution is **capability-passing style**: instead of using a global effect handler stack (as in algebraic effect systems like Koka) or monad transformer stacks (as in Haskell), effects are represented as first-class capabilities passed through function parameters. This delivers type-safe, extensible effect handling without runtime overhead, and it can be encoded as a Scala library without compiler modifications.

## Background: The Problem with Effect Systems

Existing effect handling approaches face a trilemma:
1. **Monad transformers** (Haskell) — composable but require significant boilerplate; the stacking order matters and is fragile.
2. **Algebraic effects / delimited continuations** (Koka, Eff) — ergonomic but require language-level or runtime support for continuations.
3. **Global effect registries** — simple but not type-safe or extensible.

Capability-passing addresses all three limitations: it is type-safe (effects declared in types), extensible (new handlers without modifying existing code), and has no runtime overhead (no continuation stack, no boxing).

## Capability-Passing Style Explained

In capability-passing style, an **effect** is an interface (capability) that the function receives as a parameter:

```scala
// Declare an effect as a capability trait
trait Raise[E]:
  def raise(e: E): Nothing

// A function that uses the Raise capability
def divide(a: Int, b: Int)(using r: Raise[String]): Int =
  if b == 0 then r.raise("Division by zero")
  else a / b
```

The effect is made explicit in the function signature — not as a monad wrapper around the result, but as an implicit/context parameter. Callers must either:
1. Provide a handler (an implementation of `Raise[E]`) — this is effect handling.
2. Accept the capability as a parameter themselves — this is effect propagation.

This is called "capability-passing" because the effect capability is passed through the call graph rather than intercepted by a global stack.

## Effect Handlers

A handler provides a concrete implementation of the capability and defines what happens when each operation is called. Handlers are scoped: they apply within a delimited region:

```scala
// A handler that converts Raise[String] to Either[String, A]
def handleRaise[A](body: Raise[String] ?=> A): Either[String, A] =
  var result: Either[String, A] = null
  given r: Raise[String] with
    def raise(e: String): Nothing =
      result = Left(e)
      throw new AbortException  // escape from delimited scope
  try
    result = Right(body)
    result
  catch case _: AbortException => result
```

The paper demonstrates that this pattern is semantically equivalent to algebraic effect handlers with shallow continuations, while being implementable purely as a library.

## Extensibility

The capability-passing approach directly solves the Expression Problem for effects:
- New **operations** can be added by extending the capability trait.
- New **handlers** can be written without modifying existing code.
- Effect composition is achieved by nesting handler scopes.

This is in contrast to monad transformer stacks, where adding a new effect type requires threading it through all existing layers.

## Type Safety

Effect types appear in the type signature as implicit parameters (or context functions in Scala 3). The compiler enforces that:
- Every required capability must be in scope.
- Effects are lexically scoped — they cannot be used outside the handler's scope.
- Unhandled effects are compile-time errors.

In the Effekt language (the standalone descendant), effect types use a notation `Int / { raise }` meaning "returns Int with effect `raise` pending."

## Effekt Language (Standalone)

The paper proved the concept; the standalone **Effekt** language (https://effekt-lang.org/) takes it further:

- **Lexical effect handlers**: handlers are tied to a lexical scope, preventing effects from "leaking" past their handler.
- **Lightweight effect polymorphism**: higher-order functions like `map` work without explicit effect annotations — the system infers effect polymorphism from context.
- **Static effect safety**: all effects must be handled; unhandled effects are compile-time errors.
- **Generators, coroutines as libraries**: handlers can resume at the call site, so generators and coroutines are expressible as user-space libraries.

Example Effekt type: `def sqrt(n: Int): Int / { raise[String] }` — returns `Int` with the `raise[String]` effect pending.

## Scala Effekt Library (Discontinued)

The original Scala library (`de.b-studios %% effekt % 0.4-SNAPSHOT`) was tested with Scala 2.12/2.13. It was discontinued in 2020 when the standalone Effekt language superseded it. The library demonstrated that:
- Capability-passing is implementable as a Scala library without compiler support.
- It provides a practical alternative to monad transformers in Scala.

## Relation to Scala 3 Capabilities

Scala 3's `scala.caps.Capability` and the `-Ycc` capture checking experiment share significant conceptual ground with the Effekt research:
- Both treat effects as capabilities passed through the call graph.
- Both use lexical scoping to enforce effect containment.
- Scala 3's `A ?=> B` context functions can encode capability-passing directly.

Effekt's research thus predicts and validates the direction Scala 3 is taking with capture checking.

## Relation to Kyo

Kyo's `A < S` type can be understood as a compile-time record of pending capabilities, while Effekt's capabilities are passed as runtime values. Both achieve type-safe effect tracking but through different mechanisms:
- Kyo: monadic suspension with type-level effect intersection
- Effekt: capability-passing with lexical handlers and no monadic wrapper

## See Also

- [Kyo Effects](kyo-effects.md)
- [Scala Effect Libraries Comparison](scala-effect-libraries-comparison.md)
- [Scala Caps Capability](scala-caps-capability.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
- [Effect Systems Overview](../effect-systems/effect-systems-overview.md)
