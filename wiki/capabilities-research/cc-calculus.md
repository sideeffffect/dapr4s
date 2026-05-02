# CC Calculus: The Theory Behind Scala 3 Capture Checking

> Sources: Aleksander Boruch-Gruszecki, Jonathan Brachthäuser, Edward Lee, Ondřej Lhoták, Martin Odersky — TOPLAS 2023
> Raw: [2021-05-25-capturing-types](../../raw/capabilities-research/2021-05-25-capturing-types.md)

## Overview

"Capturing Types" (TOPLAS 2023) introduces the **CF<: calculus** — the formal foundation for Scala 3's `-Ycc` capture checking feature. The core idea is simple but powerful: every type carries a **capture set**, a set of variable names from the enclosing scope that the value may reference. This single addition to System F<: is enough to enable static tracking of effects, resource lifetimes, and capability confinement.

The paper answers the question: how can a type system know, without runtime checks, that a file handle won't escape the `withFile { ... }` block that created it? The answer is capture sets — if the type of a value includes `fh` in its capture set, the type checker can verify that the value doesn't escape the scope where `fh` is bound.

## Key Contributions

1. **CF<: Calculus** — a conservative, lightweight extension of System F<: where types are annotated `T^C` with a capture set `C`. Subtyping is extended: `T^C <: T^D` if `T <: T` and `C ⊆ D`.

2. **Capture Sets** — sets of in-scope variable names. `T^{}` is a pure type (no capabilities). `T^{cap}` can use any capability. `T^{x, y}` references the resources accessible through `x` and `y`.

3. **Effect Polymorphism** — functions can be polymorphic over capture sets, enabling generic code that propagates effect information without fixing which effects are used.

4. **Box Types** — `□T^C` hides a capture set behind a box, enabling capability-carrying values to be stored in containers. Unboxing reveals the full capture information.

5. **Capability Confinement** — formal proof that a capability cannot escape its binding scope, formalized as: if `x` is bound in a handler and `e : T` where `x ∉ T`, then the capability is confined.

6. **Coq Mechanization** — type soundness and confinement proofs mechanized in Coq.

## Core Concepts

### Type Annotations: `T^C`

The fundamental addition is the superscript annotation:

```
String^{}           -- pure string, no capabilities captured
FileHandle^{fh}     -- a file handle whose resource is tracked via fh
() ->{io} Unit      -- a function that uses the io capability
() -> Unit          -- a pure function (empty capture set)
```

The capture set annotation makes the type system aware of what resources a value holds. This is unlike traditional type systems where two closures of the same function type are indistinguishable regardless of what they capture.

### Subtyping and Capture

Capture sets are ordered by subset inclusion: `T^C <: T^D` when `C ⊆ D`. This means:
- A pure value `T^{}` can be used anywhere a capability-using value `T^{x}` is expected (widening)
- A value capturing only `x` cannot be used where a pure value is required (narrowing would be unsound)

### Capability Confinement in Practice

The key safety pattern enabled by CF<::

```scala
def withFile[T](path: Path)(body: FileHandle^ => T): T =
  val fh = openFile(path)  // fh: FileHandle^{cap}
  val result = body(fh)
  closeFile(fh)
  result
```

The return type `T` does not include `fh` in its capture set (by the avoidance requirement). Therefore, `result` cannot reference `fh`. The compiler rejects any body that tries to return a value capturing `fh`.

### Effect Polymorphism

A function polymorphic over effects:

```scala
def map[A, B, C^](f: A ->{C} B)(xs: List[A]): List[B ->{C} ?]
```

Here `C^` is a capture variable — the function `map` is polymorphic over which capabilities `f` uses. The result type reflects whatever capabilities `f` requires. This is how generic combinators like `map`, `flatMap`, `foreach` preserve effect information.

### Box/Unbox

The box type `□T` temporarily "hides" a capture set. This is needed when storing capability-carrying values in containers whose type parameter `T` doesn't carry capture annotations:

```scala
val box: □(FileHandle^{fh}) = Box(fh)  // hide capture info
// ...
val fh2: FileHandle^{fh} = unbox(box)  // restore capture info
```

Boxes are the predecessor of the reach capability mechanism developed in "What's in the Box" (OOPSLA 2025).

## Relationship to Scala 3

CF<: was the direct blueprint for Scala 3's experimental `-Ycc` capture checking flag introduced around Scala 3.3. Key correspondences:

| CF<: Concept | Scala 3 Syntax |
|---|---|
| `T^{x}` | `T^{x}` |
| `T^{cap}` | `T^` or `T^{cap}` |
| `T^{}` (pure) | `T` (no annotation) |
| Pure function | `A => B` |
| Impure function | `A ->{cap} B` or `A -> B` (with cap inferred) |
| Box type | `T` in generic position (handled by rcaps in later work) |

## Relevance to Safe Scala / DAPR Project

CF<: is the theoretical core of everything the Safe Scala library does. Every capability type annotation in the library (`DaprClient^`, `ActorRuntime^`, etc.) is grounded in this calculus. The confinement guarantee — that DAPR capabilities don't escape their scoped handlers — is exactly the CF<: confinement theorem applied to the DAPR domain.

Understanding CF<: is essential for:
- Reasoning about why the type annotations are sound
- Debugging confusing error messages from `-Ycc`
- Extending the library with new capability types
- Understanding how effect polymorphism works in the generic DAPR combinators

## See Also

- [Scoped Capabilities for Polymorphic Effects](scoped-capabilities-polymorphic-effects.md) — CCsubBox, the refined calculus
- [Reach Capabilities](reach-capabilities.md) — generics extension (System Capless)
- [Capabilities for Safe Agents](capabilities-for-safe-agents.md) — application of these ideas to AI safety
- [Algebraic Effects and Handlers](algebraic-effects-handlers.md) — the effect model that motivated capability confinement
- [Capture Checking Overview](../scala-capture-checking/capture-checking-overview.md) — Scala 3 implementation of CC
