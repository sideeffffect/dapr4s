# How to Use Capture Checking

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [how-to-use](../../raw/scala-capture-checking/2026-05-01-how-to-use.md); [internals](../../raw/scala-capture-checking/2026-05-01-internals.md)
> Updated: 2026-05-01

## Overview

Capture checking is an experimental opt-in feature. It requires a Scala 3 nightly build and is enabled per-file via language imports. Separation checking is an additional opt-in extension on top of capture checking.

## Enabling

```scala
// Capture checking only:
import language.experimental.captureChecking

// Also separation/mutability checking:
import language.experimental.captureChecking
import language.experimental.separationChecking
```

## Getting Started

**SBT template:** https://github.com/lampepfl/scala3-cc-template

**Scala CLI:**

```bash
scala -S 3.nightly -language:experimental.captureChecking
```

Or in a `.scala` file with a using directive:

```scala
//> using scala 3.nightly
import language.experimental.captureChecking
```

## Useful Compiler Flags

| Flag | Effect |
|---|---|
| `-Vprint:cc` | Shows the program after capture checking with inferred capturing types |
| `-Ycc-verbose` | More detailed display of capabilities and capturing types |
| `-Ycc-debug` | Implementation-level information for debugging the checker itself |

## API Documentation

The nightly standard library API docs have capture checking enabled: https://nightly.scala-lang.org/api/

## How the Checker Works (Internals)

The capture checker is a propagation constraint solver that runs after type-checking:

1. Constraint variables are introduced for inferred type components, method/class references, and constructor parameters
2. Explicit capture sets are treated as constants
3. Subtype requirements between capturing types are checked through subcapturing tests
4. When the lower set of a subcapturing test is a variable, the upper set is recorded as a superset; when the upper set is a variable, elements are propagated to it and onwards through known supersets

**Type mapping** during transformations tracks variance: covariant positions use capture sets, contravariant positions use empty sets, nonvariant creates propagated type ranges.

**Capture tunneling** is implemented via virtual box/unbox operations (like implicit conversions): boxing hides a capture set, unboxing recovers it. No runtime cost.

**Debug variable identifiers** with `-Ycc-debug`:

| Letter | Source |
|---|---|
| `V` | Regular variable |
| `M` | Mapped |
| `B` | Bijective mapping |
| `F` | Filtered |
| `I` | Intersected |
| `D` | Difference |
| `R` | Refining class parameters |

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capturing Types](capturing-types.md)
- [Separation and Mutability](separation-and-mutability.md)
