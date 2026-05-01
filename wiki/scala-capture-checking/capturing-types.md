# Capturing Types

> Sources: Scala 3 Documentation (EPFL/Scala Team), Unknown
> Raw: [basics](../../raw/scala-capture-checking/2026-05-01-basics.md); [classes](../../raw/scala-capture-checking/2026-05-01-classes.md); [polymorphism](../../raw/scala-capture-checking/2026-05-01-polymorphism.md)
> Updated: 2026-05-01

## Overview

Capturing types are the central syntactic mechanism of Scala 3 capture checking. A capturing type `T^{c₁, ..., cₙ}` encodes not just the class of a value, but also which capabilities (`c₁...cₙ`) the value can access. This makes effects visible in the type system.

## Syntax Reference

| Syntax | Meaning |
|---|---|
| `T` | Pure type — retains no capabilities (equivalent to `T^{}`) |
| `T^{c}` | Retains capability `c` |
| `T^{c₁, ..., cₙ}` | Retains all listed capabilities |
| `T^` | Shorthand for `T^{any}` — retains arbitrary capabilities |
| `A -> B` | Pure function — captures nothing |
| `A => B` | Impure function — shorthand for `A ->{any} B` |
| `A ->{c₁, ..., cₙ} B` | Function that captures exactly `{c₁, ..., cₙ}` |
| `A ?-> B` | Pure context function |
| `A ?=> B` | Impure context function |

A `^` annotation binds more tightly than a function arrow: `A -> B^{c}` means `A -> (B^{c})`.

## Capture Sets

A capture set `{c₁, ..., cₙ}` is a set of references to capabilities. A reference is a capability when it:
- Is a method- or class-parameter, local variable, or `this` of an enclosing class
- Has a type with a non-empty capture set

The _universal capability_ `any` is the root from which all others are derived. Every capability ultimately traces back to `any`.

## Subcapturing Relation

`C₁ <: C₂` ("C₁ is covered by C₂") holds if `C₂` accounts for every element `c` of `C₁`. `C₂` accounts for `c` when:
1. `c ∈ C₂`
2. `c` refers to a parameter of class `Cls` and `C₂` contains `Cls.this`
3. `c`'s type has capture set `C` and `C₂` accounts for every element of `C`

Consequence: `{l}` where `l: Logger^{fs}` is covered by `{fs}` — the transitive capture sets propagate.

## Subtyping with Capturing Types

- Pure types are subtypes of capturing types: `T <: T^C` for any `C`
- Smaller capture sets produce subtypes: `T₁^C₁ <: T₂^C₂` if `C₁ <: C₂` and `T₁ <: T₂`
- Subtype ordering example: `A <: A^{lg} <: A^{out} <: A^{out,f} <: A^`

## By-Name Parameters

Analogous conventions to function types:

| Parameter | Meaning |
|---|---|
| `x: => T` | Actual argument can use arbitrary capabilities |
| `x: -> T` | Actual argument must be pure |
| `x: ->{c} T` | Actual argument may use only `c` |

## Lazy Vals

Lazy vals have two distinct capture sets:

1. **Initializer's capture set** — capabilities used when the lazy val is first forced
2. **Result's capture set** — capabilities retained by the computed value

Accessing a lazy val through a qualifier charges the qualifier to the current capture set, exactly like a parameterless method. A pure result can still require a capability to access if the initializer uses it.

## Capture Tunneling

When a type variable is instantiated to a capturing type, the capture is _not_ propagated to the enclosing generic constructor application. The capture "tunnels through" and reappears only when the type variable is instantiated again on access.

```scala
class Pair[+A, +B](x: A, y: B)
def x: Int ->{ct} String
def y: Logger^{fs}
val p: Pair[Int ->{ct} String, Logger^{fs}] = Pair(x, y)
// p itself has empty capture set
val f: () ->{ct} Int ->{ct} String = () => p.fst // ct reappears here
```

This is one of the key practical features of the approach — generic containers don't accumulate all the capabilities of their contents in their own types.

## Capability Classes

Classes can extend `SharedCapability` to indicate their values are always capabilities. Without an explicit capture set, such a type defaults to `{any}`. Explicit annotations like `T^{fs}` or `T^{}` are respected as written.

```scala
class FileSystem extends SharedCapability
// FileSystem = FileSystem^{any} wherever used as a capability
```

## Avoidance (Type Widening)

When a local variable `l` (with capture set `{fs}`) would appear in an outer type, the type is widened to the smallest supertype not mentioning `l`. Since `{fs}` covers `{l}`, the result type is widened to use `{fs}`. This is called _avoidance_ and is not specific to capture checking.

## Implicit vs Explicit Polymorphism

**Implicit** (recommended default): The existing `=>` / `->` distinctions already give polymorphism. Higher-order functions like `List.map` naturally handle both pure and impure arguments.

**Explicit**: Capture-set variables `X^` let APIs parameterize over capture sets:

```scala
class Source[X^]:
  private var listeners: Set[Listener^{X}]
```

Prefer implicit; use explicit only when capture relationships cannot be expressed implicitly.

**Capability members**: Capture information can be tied to object identity via path-dependent annotations `{this.X}` — useful for abstract interfaces.

## See Also

- [Capture Checking Overview](capture-checking-overview.md)
- [Capabilities and Resources](capabilities-and-resources.md)
- [Separation and Mutability](separation-and-mutability.md)
- [Capability Classifiers](capability-classifiers.md)
