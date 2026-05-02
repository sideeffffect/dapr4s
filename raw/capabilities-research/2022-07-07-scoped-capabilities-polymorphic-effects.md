# Scoped Capabilities for Polymorphic Effects
> Source: https://arxiv.org/abs/2207.03402
> Collected: 2026-05-01
> Published: 2022-07-07 (arXiv v1); 2022-07-22 (v2)

## Metadata

**Authors:** Martin Odersky, Aleksander Boruch-Gruszecki, Edward Lee, Jonathan Brachthäuser, Ondřej Lhoták
**Affiliation:** EPFL; University of Tübingen; University of Waterloo
**arXiv:** https://arxiv.org/abs/2207.03402
**DOI:** https://doi.org/10.48550/arXiv.2207.03402
**Pages:** 39 pages
**Category:** cs.PL

## Abstract

Type systems usually characterize the shape of values but not their free variables. However, many desirable safety properties could be guaranteed if one knew the free variables captured by values. We describe CCsubBox, a calculus where such captured variables are succinctly represented in types, and show it can be used to safely implement effects and effect polymorphism via scoped capabilities.

## Key Contributions

1. **CCsubBox Calculus**: A formal calculus extending subtype-bounded polymorphism where every type carries a capture set representing the free variables the value may reference. This is a refinement over CF<: with a cleaner treatment of the box/unbox mechanism.

2. **Captured Variables in Types**: The type `T^{x, y}` explicitly records that a value of this type may reference variables `x` and `y` from the enclosing scope. Pure values have empty capture sets `T^{}`.

3. **Scoped Capabilities**: Effects are implemented as capabilities — opaque values whose type records the specific scope variable they are bound to. An effect operation requires the capability in its capture set; the scoping of the capability binding is enforced by capture checking.

4. **Effect Polymorphism via Capture Polymorphism**: A function can be polymorphic in its capture set, enabling generic code to work with effects without fixing which specific effects are used. This is the formal basis for effect polymorphism in Scala 3's capture checking.

5. **Intuitive Types for Standard Patterns**: The paper shows that CCsubBox assigns natural, readable types to common programming patterns: closures, iterators, exception handlers, async/await, and resource management via `using`/`try`-with-resources analogs.

6. **Implementation Guidance**: The calculus directly informed the design of Scala 3's `-Ycc` capture checking experimental feature.

## Core Concepts

### Capture Sets and the Box Type

Types are annotated `T^C` where `C` is a capture set (a set of in-scope variable names). The special annotation `T^{cap}` means the value captures the universal root capability (can use any resource). 

The **box type** `□T^C` wraps a value with capabilities, hiding the capture set at the outer level. This enables storing capability-carrying values in containers. The corresponding `unbox` operation restores visibility of the capture set. This box/unbox duality is central to safe generic programming with capabilities.

### Effect Polymorphism

A generic function `[C] (A^{} -> B^C)^{} -> B^C` is polymorphic over capture set `C`. The caller instantiates `C` with the specific capability variable in scope. This is analogous to effect row polymorphism in Koka or Frank, but implemented via capture sets rather than a separate effect layer.

### Scoped Effect Handlers

Effect handlers are represented as higher-order functions that receive a capability as argument. The handler's body is a function that closes over the capability. Capture checking guarantees:
1. The capability variable does not appear in the return type of the handler (avoidance)
2. Therefore no reference to the capability escapes the handler
3. Therefore no effect operation can be invoked after the handler returns

This gives the same safety guarantees as algebraic effect handlers, but via the type system rather than a separate mechanism.

### Relationship to Other Work

- Refines CF<: (TOPLAS 2023) with better treatment of generics and boxes
- Directly implemented as Scala 3 capture checking
- Extended by System Capless (OOPSLA 2025) for full generics support
- Inspired by scoped effects in Frank, Koka, and Effekt
