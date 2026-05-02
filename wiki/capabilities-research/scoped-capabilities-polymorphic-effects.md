# Scoped Capabilities for Polymorphic Effects

> Sources: Martin Odersky, Aleksander Boruch-Gruszecki, Edward Lee, Jonathan Brachthäuser, Ondřej Lhoták — arXiv 2022
> Raw: [2022-07-07-scoped-capabilities-polymorphic-effects](../../raw/capabilities-research/2022-07-07-scoped-capabilities-polymorphic-effects.md)

## Overview

This paper (arXiv 2022) presents **CCsubBox**, the refined calculus underlying Scala 3's capture checking. It expands on the foundational CF<: calculus (TOPLAS 2023) with a cleaner treatment of the box/unbox mechanism and a comprehensive demonstration that capture-tracked types can express the full range of effect patterns: state, exceptions, async/await, iterators, resource management, and algebraic effects.

The central thesis is that **effects can be safely implemented via scoped capabilities** — rather than encoding effects in the type system as a separate layer (like effect rows in Koka or Frank), you encode "permission to perform an effect" as a regular value with a capability type. The type checker's capture analysis then enforces that this permission doesn't escape its designated scope.

## Key Contributions

1. **CCsubBox Calculus** — a calculus with subtype-bounded polymorphism, full capture set annotation, and a clean box/unbox mechanism for storing capabilities in containers.

2. **Captured Variables = Captured Effects** — the paper demonstrates that tracking which variables a value captures is equivalent (in expressiveness) to tracking which effects it may perform, eliminating the need for a separate effect layer.

3. **Effect Polymorphism via Capture Polymorphism** — capture set variables `C^` enable generic functions to be polymorphic over which effects their callback arguments use, solving the "effect pollution" problem without effect rows.

4. **Standard Patterns Covered** — detailed worked examples showing natural, readable types for: closures, iterators, resource managers, exception handlers, async/await (via the gears library), and continuations.

5. **Design Guidance for Scala 3** — this paper is the primary theoretical reference for the Scala 3 `-Ycc` feature design.

## Core Concepts

### Captured Variables in Types: The Insight

In conventional type systems, a function type `A => B` says nothing about what the function body does. In CCsubBox, the type `(A -> B)^{x, y}` says the function *captures* variables `x` and `y` — it may invoke any operation that `x` or `y` permit. If `x` is a database handle, the function may perform database operations.

This is the key insight: **capabilities are just variables**, and tracking which variables a value captures is equivalent to tracking which effects it has permission to use. No separate effect layer is needed.

### Scoped Capabilities Pattern

The canonical pattern for scoped effects:

```scala
// Define the capability type
trait FileSystem:
  def readFile(path: Path): String
  def writeFile(path: Path, content: String): Unit

// Scoped handler — capability is created and destroyed within the block
def withFileSystem[T](body: FileSystem^ => T): T =
  val fs = new FileSystem { ... }  // create real implementation
  body(fs)
  // fs goes out of scope here — capture checker ensures body's result
  // does not reference fs
```

After `withFileSystem` returns, it is statically guaranteed (by capture checking) that no reference to `fs` exists anywhere. This is the fundamental capability scoping guarantee.

### Effect Polymorphism

The problem: a function like `List.map` should work whether its callback uses effects or not. Without effect polymorphism, you need two versions:

```scala
def map[A, B](f: A => B)(xs: List[A]): List[B]           // pure f only
def mapIO[A, B](f: A ->{io} B)(xs: List[A]): List[B]     // io f only — but what about other effects?
```

CCsubBox solves this with a capture variable:

```scala
def map[A, B, C^](f: (A ->{C} B)^{C})(xs: List[A]): List[B] // any capability C
```

The capture variable `C^` is instantiated at the call site with whatever capability the callback uses. The return type `List[B]` has an empty capture set, so no capabilities leak out through the result. This is the precise formal model behind how Scala 3's generic standard library methods handle effects.

### Pure vs. Impure Functions

CCsubBox distinguishes:

| Scala 3 Syntax | Meaning |
|---|---|
| `A => B` | Pure function — captures nothing, no effects |
| `A -> B` | Potentially impure function — may capture anything |
| `A ->{c} B` | Function capturing exactly capability `c` |
| `A ->{c, d} B` | Function capturing `c` and `d` |

Pure functions (`A => B`) compose freely without contaminating caller types. This is the foundation for the "direct style" effect discipline: write code that looks synchronous/imperative, but the type system tracks and enforces which effects each piece uses.

### Box Types

When a capability must be stored in a container:

```scala
val stored: Box[FileSystem^{fs}] = box(fs)  // "box" hides the capture set
// ...
val fs2: FileSystem^{fs} = unbox(stored)    // "unbox" restores it
```

Box types allow generic containers to hold capabilities without polluting the container's type with the specific capture set. This is the predecessor mechanism to reach capabilities (rcaps) from OOPSLA 2025.

### Avoidance

A crucial auxiliary property: when a value `v` of type `T^C` is returned from a scope where some variable `x ∈ C` is bound, the type `T^C` must be **avoided** — `x` cannot appear in the return type. If avoidance is impossible, the value cannot be returned. This is how capability confinement is enforced: a capability bound by a handler cannot escape that handler.

## Relevance to Safe Scala / DAPR Project

This paper is the direct theoretical basis for how capability types are used in the Safe Scala library:

- **DAPR building block capabilities** (e.g., `StateStore^`, `PubSub^`, `ActorRuntime^`) follow the scoped capability pattern: they are created by `withDapr { ... }` style handlers and are statically confined to that scope.

- **Effect-polymorphic combinators** in the library (functions that accept user-provided callbacks, like workflow activity handlers) use capture polymorphism to correctly propagate whatever capabilities the user's code needs.

- **Direct-style async** via the gears library — which this paper explicitly covers as a worked example — maps directly to how async DAPR operations work in Safe Scala.

- The **`->{cap}` notation** visible in compilation errors and type signatures throughout the library comes directly from this calculus.

## See Also

- [CC Calculus (Capturing Types)](cc-calculus.md) — the predecessor CF<: calculus
- [Reach Capabilities](reach-capabilities.md) — generics extension
- [Algebraic Effects and Handlers](algebraic-effects-handlers.md) — what scoped capabilities implement
- [Polymorphic Reachability Types](polymorphic-reachability-types.md) — parallel approach from the separation logic perspective
