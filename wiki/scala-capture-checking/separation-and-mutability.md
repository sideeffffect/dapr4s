# Separation and Mutability

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [separation-checking](../../raw/scala-capture-checking/2026-05-01-separation-checking.md); [mutability](../../raw/scala-capture-checking/2026-05-01-mutability.md); [scoped-capabilities](../../raw/scala-capture-checking/2026-05-01-scoped-capabilities.md)
> Updated: 2026-05-01

## Overview

Separation checking and stateful capabilities together form Scala 3's mechanism for safe mutation. Stateful capabilities model read vs. write access. Separation checking enforces that mutable capabilities are not aliased — analogous to Rust's borrow checker, but using capabilities instead of regions. Both build on top of capture checking and are enabled by an additional language import.

## Stateful Capabilities

Classes extend `caps.Stateful` to indicate they can consult and modify program state.

The key trait hierarchy for mutation:

| Trait | Meaning |
|---|---|
| `Stateful` | Can consult and modify state |
| `Unscoped` (extends `ExclusiveCapability`) | Can escape defining environment; no captured external resources |
| `Mutable` (extends `Stateful`, `Unscoped`) | Stateful and unscoped — the standard base for mutable data structures |

### Update Methods

The `update` soft modifier marks methods that mutate state:

```scala
abstract class Buffer[T] extends Mutable:
  update def append(elem: T): Unit  // mutates
  def apply(pos: Int): T            // read-only
  def size: Int                     // read-only
```

Ordinary methods in a `Stateful` class are checked as read-only with respect to the receiver — they cannot call `update` methods.

### Read vs. Write Access Modes

For a mutable capability `x`:
- `x` — full access (can call `update` methods)
- `x.rd` — read-only access (can only observe state)

A reference of type `Buffer` allows only regular methods. `Buffer^` (with the universal capability) also allows update methods.

## Enabling Separation Checking

```scala
import language.experimental.captureChecking
import language.experimental.separationChecking
```

Separation checking is less mature than capture checking and its safety/expressivity balance may still evolve.

## The Core Idea: Aliasing is Forbidden for Exclusive Capabilities

Each occurrence of `any` (including `^`) is interpreted as a separate top capability. The system tracks which capabilities are _hidden_ by each `any`. Any capability hidden by `anyᵢ` cannot be referenced independently or hidden by another `anyⱼ` visible in the same code.

**These checks apply only to exclusive capabilities.** `SharedCapability` types are exempt.

```scala
val y = Ref(1)
val x: Ref^ = y   // x hides y under a fresh any
x.get
y.get             // error: y is hidden by x
```

## Separation Checks in Four Contexts

### 1. Function Applications

The hidden set of each `any`-typed argument must be separated from the capture sets of all other arguments and the result:

```scala
multiply(a, b, a)  // error: a in hidden set of last arg, also first arg
```

Exception: if a parameter explicitly names a conflicting parameter in its capture set, no error is reported:

```scala
def seq(f: () => Unit, g: () ->{any, f} Unit): Unit = f(); g()
seq(plusOne, plusOne) // ok: g explicitly names f
```

### 2. Statement Sequences

When capability `x` is used, `{x}` must be separated from hidden sets of all previous definitions in the sequence.

### 3. Types

Top capabilities in a type must not have interfering hidden sets:

```scala
val b: (Ref^, Ref^) = (a, a)       // error: both ^s hide a
val d: (Ref^{a}, Ref^{a}) = (a, a) // ok: no hidden sets
```

### 4. Return Types

The hidden set of an `any` in a return type cannot reference exclusive capabilities defined outside the function (including parameters):

```scala
def newRef(): Ref^ = Ref(1)  // ok: fresh
def incr(a: Ref^): Ref^ = a  // error: a would be in hidden set
```

## `fresh` in Function Results

While `any` in a return type is the enclosing scope's top capability, `fresh` in function type results is existentially bound — each call yields a distinct capability:

```
() -> Ref^{fresh}   means   () -> ∃fresh. Ref^{fresh}
```

```scala
val mkRef: () -> Ref^{fresh} = () => Ref(1)
val a = mkRef()  // Ref^{fresh₁}
val b = mkRef()  // Ref^{fresh₂}
// fresh₁ ≠ fresh₂ → a and b are separated
```

## Consume Parameters and Linear Access

The `consume` modifier on a parameter enforces that the argument is not used after the call:

```scala
def incr(consume a: Ref^): Ref^ =
  a.set(a.get + 1)
  a

val a1 = Ref(1)
val a2 = incr(a1)  // a1 consumed — cannot use a1 again
val a3 = incr(a2)  // a2 consumed
```

`consume` on a method implies `update` in `Mutable` classes.

### Linear Buffers

`consume` enables purely-functional-style APIs over mutable structures:

```scala
def linearAdd[T](consume buf: Buffer[T]^, elem: T): Buffer[T]^ =
  buf += elem

def contents[T](consume buf: Buffer[T]): Int ->{buf.rd} T =
  i => buf(i)
// Consuming buf.rd freezes the buffer: no further writes, reads still allowed
```

## The `freeze` Wrapper

Converts a mutable structure to an immutable type after initialization:

```scala
import caps.freeze

val f: IArr[String] =
  val a = Arr[String](2)
  a(0) = "hello"; a(1) = "world"
  freeze(a)  // consumes a, returns it with empty capture set
```

`freeze` is defined as `def freeze(consume x: Mutable): x.type` — safe only with separation checking enabled.

## Scoped Capabilities and the Level Hierarchy

The `any` capability has a scope-dependent meaning. Inner `any`s cannot flow outward:

```scala
var esc: File^ = null
withFile("test.txt"): f =>
  esc = f  // error: f's any₂ cannot flow into esc's any₁
```

Parallels with Rust:
- Capability names ≈ lifetime parameters
- Capture sets ≈ lifetime bounds
- Level containment ≈ outlives relation

Scala computes levels automatically from program structure.

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capturing Types](capturing-types.md)
- [Capabilities and Resources](capabilities-and-resources.md)
- [Capability Classifiers](capability-classifiers.md)
