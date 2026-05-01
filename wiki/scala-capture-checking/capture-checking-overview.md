# Capture Checking Overview

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [overview](../../raw/scala-capture-checking/2026-05-01-overview.md); [basics](../../raw/scala-capture-checking/2026-05-01-basics.md); [cc](../../raw/scala-capture-checking/2026-05-01-cc.md)
> Updated: 2026-05-01

## Overview

Capture checking is a Scala 3 experimental type system extension that tracks which capabilities (resources, effects) a value can access. It gives the type system the ability to describe and constrain the effects of computations, prevent resource leaks, enforce lifetimes, and distinguish read-only from mutable access — all statically, without runtime overhead.

## Motivation and Core Problem

The classic try-with-resources pattern has a silent safety gap:

```scala
def usingLogFile[T](op: FileOutputStream => T): T =
  val logFile = FileOutputStream("log")
  val result = op(logFile)
  logFile.close()
  result

val later = usingLogFile { file => () => file.write(0) }
later() // crash: file already closed
```

Capture checking closes this gap statically by attaching capture sets to types:

```scala
def usingLogFile[T](op: FileOutputStream^ => T): T = ...
// val later = usingLogFile { f => () => f.write(0) } // compile error
```

## Enabling Capture Checking

```scala
import language.experimental.captureChecking
// optionally, for mutability/separation:
import language.experimental.separationChecking
```

Both are experimental. Capture checking is considered mature; separation checking is more fluid.

## What Capabilities Are

A capability is a value "of interest" — file handles, access tokens, mutable data structures, async contexts. Capabilities are designated by having their type extend `Capability` (or `SharedCapability`, `ExclusiveCapability`).

A value becomes a capability when its type has a non-empty capture set. The `any` (universal) capability is the root from which all others are derived.

## Capturing Types

The core syntactic form is `T^{c₁, ..., cₙ}` — the type of values of class `T` that retain capabilities `c₁, ..., cₙ`.

| Notation | Meaning |
|---|---|
| `T` or `T^{}` | Pure — retains no capabilities |
| `T^{c}` | Retains capability `c` |
| `T^{c₁, c₂}` | Retains both capabilities |
| `T^` or `T^{any}` | Retains arbitrary capabilities |

The subtype ordering: smaller capture sets → smaller (more restricted) types:
```
A  <:  A^{lg}  <:  A^{out}  <:  A^{out, f}  <:  A^
```

## Function Types

| Type | Meaning |
|---|---|
| `A -> B` | Pure function — captures nothing |
| `A => B` | Impure function — alias for `A ->{any} B` |
| `A ->{c₁, c₂} B` | Function capturing only `c₁`, `c₂` |
| `A ?-> B` | Pure context function |
| `A ?=> B` | Impure context function |

Methods never directly capture capabilities — references count toward the enclosing object's capture set.

## Escape Checking

Capture sets can only contain capabilities visible at the point where the set is defined. A local capability `f` cannot appear in a type defined outside the scope where `f` is visible. Attempts to store such a capability (in a closure or a global variable) are rejected at compile time.

The _avoidance_ mechanism handles widening: if a local variable `l` with capture set `{fs}` cannot appear in an outer type, the type is widened to use `{fs}` instead.

## Monotonicity Rule

In a class `C` with field `f`, the capture set `{this}` covers `{this.f}` and any application of `this.f` to pure arguments. This means the capability of the whole object accounts for all capabilities reachable through it.

## Broader Applications

Capture checking enables solutions for:
- Checked exceptions (via `CanThrow` capabilities)
- Effect polymorphism
- The "what color is your function?" async/sync mixing problem
- Region-based allocation
- Reasoning about capabilities associated with memory locations

## See Also

- [Capturing Types](capturing-types.md)
- [Capabilities and Resources](capabilities-and-resources.md)
- [Safe Exceptions](safe-exceptions.md)
- [Separation and Mutability](separation-and-mutability.md)
- [How to Use Capture Checking](how-to-use.md)
- [Capability-Based Effects](../effect-systems/capability-based-effects.md)
