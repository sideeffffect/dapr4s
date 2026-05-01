# Direct-style Effects Explained

> Source: https://noelwelsh.com/posts/direct-style/
> Collected: 2026-05-01
> Published: 2024-04-24

**Author:** Noel Welsh

## Overview

This post explores direct-style effects (also called algebraic effects and effect handlers), an emerging programming paradigm that enables developers to write code in a natural style without monads, but still get the benefits of reasoning and composition.

## Core Problem & Values

Welsh emphasizes that functional programming prioritizes **reasoning** and **composition**. Since side effects undermine both, effect systems exist to control effects while maintaining these principles:

> "Side effects stop us achieving both of these, but every useful program must interact with the world in some way."

## Design Space Considerations

### Direct vs. Monadic Style

Direct style mirrors conventional programming:

```scala
val a: A = ???
val b = doSomething(a)
```

Monadic style requires transformation using `flatMap` chains or `for` comprehensions, creating a learning barrier and program-wide restructuring requirement.

### Description vs. Action

Effective composition requires separating effect description from execution. Calling `println` immediately executes the effect, preventing composition. Desired behavior: `Effect.println(...).foregroundBrightRed` combines effects descriptively before running them.

### Reasoning About Effects

Monadic signatures like `def method(): IO[Unit]` provide composability advantages over untyped side effects, enabling effect manipulation and cancellation through methods like `handleError`.

## Implementation in Scala 3

The approach leverages **context functions** (functions with implicit parameters). A `Print[A]` type represents a program description that may print and compute type `A`:

```scala
type Print[A] = Console ?=> A
```

Context functions automatically convert expressions to the required type when type annotations indicate a context function target. Automatic given-value application enables composition without explicit threading.

## Multi-Effect Composition

Multiple effects combine straightforwardly by adding parameters:

```scala
val printSample: (Console, Random) ?=> Unit =
  Print { /* ... */ }
```

## Control-Flow Effects

Effects manipulating control flow (error handling, concurrency) require **continuations** — values representing "the rest of the program" that handlers can resume. Welsh notes monads are mathematically equivalent to delimited continuations.

Scala 3's `boundary` mechanism demonstrates error-handling implementation:

```scala
def run[A](raise: Raise[A]): A = {
  boundary[A] {
    given error: Error[A] = new Error[A]
    raise
  }
}
```

## Capture Checking & Type Safety

**Capturing Types** (experimental in Scala 3) prevent unsafe closure captures of effect handlers outside valid scopes, addressing potential runtime exceptions. This feature also enables automatic type inference for multi-effect compositions.

## Comparative Advantages

Direct-style effects offer ergonomic benefits over monads:

> "I think they are much more ergonomic than monadic effects, which in turn makes them accessible to a wider range of programmers."

## Further Resources

The author recommends:
- Unison's abilities documentation
- Gears concurrency library
- Academic papers: "Capturing Types" and "Handlers in Action"
