# What's in the Box: Ergonomic and Expressive Capture Tracking over Generic Data Structures
> Source: https://arxiv.org/abs/2509.07609
> Collected: 2026-05-01
> Published: 2025-09-09 (OOPSLA 2025, DOI 10.1145/3763112)

## Metadata

**Authors:** Yichen Xu, Oliver Bračevac, Cao Nguyen Pham, Martin Odersky
**Affiliation:** EPFL, Lausanne, Switzerland
**Venue:** OOPSLA 2025
**DOI:** https://doi.org/10.1145/3763112
**arXiv:** https://arxiv.org/abs/2509.07609
**License:** (extended version on arXiv)

## Abstract

Capturing types in Scala unify static effect and resource tracking with object capabilities, enabling lightweight effect polymorphism with minimal notational overhead. However, prior to this work, capturing types could not track capabilities embedded within generic data structures — the type system had no way to "name what's in the box." This paper develops System Capless, a new theoretical foundation for capturing types that introduces existential and universal capture set quantification. On top of this, it presents reach capabilities (rcaps), a novel surface-level mechanism for witnessing existentially quantified capture sets inside the boxes of generic types, without exposing existential syntax to the user. The metatheory is fully mechanized in Lean. The system has been implemented via a complete reimplementation of Scala 3's capture checking, with the entire Scala collections library and asynchronous programming library successfully migrated, demonstrating minimal-to-zero notational overhead in a vast majority of cases.

## Key Contributions

1. **System Capless**: A new formal foundation for capturing types providing existential and universal capture set quantification — the theoretical basis enabling capability tracking through generic type abstraction boundaries.

2. **Reach Capabilities (rcaps)**: A surface-level mechanism allowing programmers to name and witness existentially quantified capabilities inside generic containers ("boxes") without requiring explicit existential types. Programmatically, this allows code like `box.reach.capability` to extract and use capabilities stored within generic structures.

3. **Lean Mechanization**: Complete formal proofs of type soundness and scope safety in Lean, validated via a type-preserving translation from System Capless to a known sound calculus.

4. **Production Implementation**: Full reimplementation of Scala 3's capture checking compiler plugin, with migration of:
   - The entire Scala standard collections library
   - The asynchronous programming library (gears/Async)
   - Demonstrating minimal annotation overhead in practice

## Core Concepts

### The Problem: Capabilities in Generic Boxes

Prior capture checking systems could track a capability `c` flowing through concrete types but lost track of it once `c` was stored inside a generic container like `List[T]`. The type system could not distinguish `List[Int]` (no capabilities) from `List[Cap]` (contains a capability). This "opacity of generics" blocked ergonomic use of capturing types in realistic Scala programs.

### Existential and Universal Capture Set Quantification

System Capless introduces:
- **Existential capture sets** (`∃cs. T^cs`): A type that contains some unknown capture set, analogous to existential types for values. When a generic container holds a capability, this is modeled as an existential capture set.
- **Universal capture set quantification** (`∀cs. T^cs`): Parametric abstraction over capture sets, enabling generic functions to operate uniformly over collections with unknown captured contents.

### Reach Capabilities

At the surface language level, existential capture sets are exposed via reach capabilities. A reach capability `x.reach` names the (existential) capture set of a value `x` of generic type. This enables:
- Passing capabilities out of containers in a type-safe way
- Writing functions polymorphic in the capability content of their container arguments
- Safe "unboxing" of capabilities that respects scope boundaries

### Relationship to Prior CC Systems

This work extends and supersedes the earlier CC<:□ calculus (Scoped Capabilities, 2022) and the CF<: calculus (Capturing Types, TOPLAS 2023) by handling the previously unaddressed case of generics. The Scala 3 reimplementation is backward-compatible with existing capture-checked code.
