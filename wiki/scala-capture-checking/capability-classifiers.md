# Capability Classifiers

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [classifiers](../../raw/scala-capture-checking/2026-05-01-classifiers.md); [advanced](../../raw/scala-capture-checking/2026-05-01-advanced.md)
> Updated: 2026-05-01

## Overview

Capability classifiers allow restricting what kinds of capabilities a parameter or result can involve. Rather than accepting any capability, a classifier-constrained context accepts only capabilities belonging to a specific domain — control flow, mutation, I/O, etc. This enables expressing precise interface contracts and enables useful filtering operations like `.only[Control]`.

## What Makes a Classifier

A trait becomes a classifier by directly extending `caps.Classifier`. Inheritance through another classifier does not itself make a trait a classifier.

```scala
// Control is a classifier (extends Classifier directly)
trait Control extends SharedCapability, Classifier

// Async is NOT a classifier (extends Classifier only indirectly via Control)
class Async extends Control
```

**Classifiers are unique:** a class cannot simultaneously extend two unrelated classifier traits. If a class transitively extends two classifiers, one must be a subtrait of the other.

## Classifier Restriction on Capture Sets

When a parameter has type `Async^` where `Async extends Control`, any actual argument can only capture capabilities whose types extend `Control`. No I/O or mutation capabilities are allowed.

This restriction applies automatically — no extra annotation needed beyond the classifier trait hierarchy.

## Predefined Classifiers

```
              Capability (sealed)
              /                 \
   SharedCapability          ExclusiveCapability
   ----------------                  |
        |                         Unscoped
     Control                      --------
     -------
```

Classifiers shown with underline:

| Trait | Classifier? | Meaning |
|---|---|---|
| `SharedCapability` | Yes | Base for shared capabilities |
| `Control` | Yes | Control-flow capabilities: throw, break, suspend |
| `Unscoped` | Yes | Can escape defining env (e.g., `Ref` cells) |
| `ExclusiveCapability` | No | Base for anti-aliased capabilities |
| `Mutable` | No | Stateful + Unscoped (mutable data structures) |

Exclusive capabilities can have shared capabilities in their capture set, but `SharedCapability` types cannot capture exclusive capabilities (since `SharedCapability` is a classifier — it can only contain capabilities of its own classifier).

## The `.only[C]` Projection

`c.only[C]` is a _restricted capability_ — the subset of `c`'s capture set that is compatible with classifier `C`. Used in result types to express that only certain categories of effects are retained:

```scala
object Try:
  def apply[T](body: => T): Try[T]^{body.only[Control]} = ???
```

When substituting a concrete capture set for `body`:
- Capabilities whose type is a known `Capability` subtype unrelated to `Control` are dropped
- Capabilities whose type extends `Control` are kept
- Fully effect-polymorphic capabilities (e.g., `proc: () => Unit`) are kept since we cannot rule out that they carry `Control` capabilities

Example:
```scala
class IO extends SharedCapability  // not Control
class Async extends Control

def test(io: IO, async: Async, proc: () => Unit) =
  val r = Try:
    // code using io, async, proc
  val _: Try[Int]^{async, proc} = r  // io dropped, async and proc kept
```

## Access Control Pattern

Classifiers enable "brand" security patterns — restricting what a callback may capture:

```scala
trait API:
  object trusted extends SharedCapability
  def runSecure(block: () ->{trusted} Unit): Unit
```

Only capabilities derived from `trusted` can be used inside `block`. Passing an untrusted logger would fail compilation.

With explicit capture-set variables, this can also be written as:

```scala
def runSecure[C^ <: {trusted}](block: () ->{C} Unit): Unit
```

## Typical Control Classifiers

The predefined `Control` classifier is used for capabilities that manage program flow rather than external state:

- `CanThrow[E]` — enables throwing exception `E`
- `Label` (boundary/break) — enables breaking out of a delimited region
- `Async` (in Gears) — enables suspension and cancellation

These capabilities can safely propagate through `Try` because their "effect" (the exception or break) is captured and re-raised by the `Try`, not lost. I/O capabilities don't have this property.

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capabilities and Resources](capabilities-and-resources.md)
- [Safe Exceptions](safe-exceptions.md)
- [Separation and Mutability](separation-and-mutability.md)
