# Reach Capabilities: Capture Tracking over Generic Data Structures

> Sources: Yichen Xu, Oliver Bračevac, Cao Nguyen Pham, Martin Odersky — OOPSLA 2025
> Raw: [2025-09-09-whats-in-the-box](../../raw/capabilities-research/2025-09-09-whats-in-the-box.md)

## Overview

"What's in the Box" (OOPSLA 2025) solves the last major gap in Scala 3's capture checking: capabilities embedded inside generic data structures. Prior versions of capturing types could track that a lambda captures a file handle or a network socket, but once that capability was placed into a `List[T]` or `Option[T]`, the type system lost track of it. This paper introduces **reach capabilities (rcaps)** and **System Capless**, which together close this gap.

The practical impact is significant: with this work, the entire Scala standard collections library and the `gears` async library have been fully annotated with capture information, demonstrating that ergonomic, zero-overhead capability tracking is achievable across a large production codebase.

## Key Contributions

1. **System Capless** — a new formal calculus that extends capturing types with existential and universal capture set quantification, providing the theoretical foundation for capability tracking through generic type boundaries.

2. **Reach Capabilities (rcaps)** — a programmer-facing mechanism to name and witness existentially quantified capture sets inside generic containers, without requiring explicit existential type syntax. The notation `x.reach` extracts the capability embedded in a generic value `x`.

3. **Lean Mechanization** — full mechanization of System Capless's metatheory in Lean, including type soundness and scope safety, validated via a type-preserving translation.

4. **Production-Scale Validation** — reimplementation of Scala 3's capture checker with complete migration of the Scala collections library and the async library, with minimal-to-zero annotation overhead in most cases.

## Core Concepts

### The Generic Box Problem

Scala's capture checking can express "this function uses a `FileSystem` capability":

```scala
def readFile(path: String)(using fs: FileSystem^): String
```

But what about a callback stored in a `List`?

```scala
val callbacks: List[() ->{fs} Unit]  // prior to rcaps: type error or annotation loss
```

The problem is that `List[T]` is generic in `T`. The capture checker needs to know that some elements of the list capture `fs`, but without rcaps it cannot represent "a `List` that contains things capturing `fs`" in a way that's both sound and usable.

### Existential Capture Sets in System Capless

System Capless introduces existential capture set quantification: `∃cs. List[T^cs]` means "a list whose elements capture some (unknown) capture set `cs`". The type system can:

- Accept a `List[() ->{fs} Unit]` as an `∃cs. List[() ->{cs} Unit]`
- When you extract an element, recover that its type is `() ->{cs} Unit` for the existential `cs`
- Pass the element to a function that needs `() ->{cs} Unit` for some `cs`

Universal quantification `∀cs. T^cs → U^{}` lets you write functions that work uniformly over containers with any capture content.

### Reach Capabilities in Practice

At the surface level, existential types are hidden behind reach capabilities. Given:

```scala
val box: List[Cap^]  // some generic container holding caps
val elem = box(0)    // elem: Cap^{box.reach}
```

The type `Cap^{box.reach}` says "a capability whose capture information comes from `box`". The `box.reach` notation is a reach capability — it names the existential capture set without exposing `∃cs` syntax.

This allows:

```scala
def mapCaps[T](xs: List[T^])(f: (x: T^{xs.reach}) => Unit): Unit
```

The function `f` receives elements whose capability is that of the list they came from.

### Scope Safety

System Capless ensures that reach capabilities cannot outlive their source. If `box` goes out of scope, any value with type `T^{box.reach}` also becomes inaccessible — the type system enforces this via the same avoidance mechanism used for ordinary capabilities. No dangling references through generic containers are possible.

## Relevance to Safe Scala / DAPR Project

This work directly enables the Safe Scala library pattern where capability-tracked resources are stored in generic data structures:

- **Actor mailboxes**: An actor's message channel can be a `Queue[Msg^{actorCap}]` — the capability tracking survives through the generic wrapper, ensuring messages cannot be leaked to actors that don't have permission.
- **Workflow state**: In the DAPR workflow model, activities hold references to workflow context capabilities. With rcaps, `List[WorkflowActivity^]` is typeable with full capability tracking.
- **Standard library integration**: Since the Scala collections library is now fully annotated, capability-safe code using `map`, `filter`, `flatMap` on capability-carrying types will type-check correctly without workarounds.

The primary bottleneck blocking idiomatic Safe Scala — the inability to use generic collections with captured resources — is solved by this paper.

## See Also

- [CC Calculus (Capturing Types)](cc-calculus.md)
- [Scoped Capabilities for Polymorphic Effects](scoped-capabilities-polymorphic-effects.md)
- [Capabilities for Safe Agents](capabilities-for-safe-agents.md)
- [Polymorphic Reachability Types](polymorphic-reachability-types.md)
