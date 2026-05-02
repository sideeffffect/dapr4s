# Capturing Types (CF<: Calculus)
> Source: https://arxiv.org/abs/2105.11896 (preprint); https://dl.acm.org/doi/10.1145/3618003 (TOPLAS 2023)
> Collected: 2026-05-01
> Published: 2021-05-25 (preprint); 2023 (TOPLAS journal)

## Metadata

**Authors:** Aleksander Boruch-Gruszecki, Jonathan Immanuel Brachthäuser, Edward Lee, Ondřej Lhoták, Martin Odersky
**Venue:** ACM Transactions on Programming Languages and Systems (TOPLAS), 2023
**DOI:** https://doi.org/10.1145/3618003
**arXiv preprint:** https://arxiv.org/abs/2105.11896
**Pages:** 23 pages, 11 figures
**Coq mechanization:** included

## Abstract

Type systems usually characterize the shape of values but not their free variables. However, there are many desirable safety properties one could guarantee if one could track how references can escape. For example, one may implement algebraic effect handlers using capabilities — a value which permits one to perform the effect — safely if one can guarantee that the capability itself does not escape the scope bound by the effect handler. To this end, we study the CF<: calculus, a conservative and lightweight extension of System F<:, to track how values and their references can be captured and escape.

## Key Contributions

1. **CF<: Calculus**: A conservative extension of System F<: (bounded parametric polymorphism) with a "captured-by" relation. Types are annotated with capture sets indicating which variables in scope the value may capture. The calculus is lightweight: it requires minimal changes to conventional type-checking rules.

2. **Capture Sets as First-Class Annotations**: Types take the form `T^C` where `C` is the capture set — a set of variables from the enclosing scope that the value may reference. The empty capture set `T^{}` denotes a pure value with no captured references.

3. **Effect Polymorphism via Capabilities**: Algebraic effect handlers can be implemented safely using capabilities. A capability is a value whose type includes a specific variable in its capture set. Effect handlers scope capabilities: when a handler exits, the capability goes out of scope, and the capture-checking rules ensure no code holds a reference to it.

4. **Succinct Representation**: Unlike region types or effect rows, capture sets are simple sets of variables already in scope. No new syntactic forms are needed beyond the `T^C` annotation. This minimizes the burden on programmers.

5. **Box Types**: The calculus introduces "box" types (`□T`) to allow temporarily hiding a capture set — enabling values with capabilities to be stored in data structures without polluting the container type with the specific captured variables. Unboxing restores the full capture information.

6. **Formal Soundness**: Type soundness and the key "capability confinement" property (a capability cannot escape its handler's scope) are proved, with proofs mechanized in Coq.

## Core Concepts

### Capture Sets

Every type is annotated with a capture set: `T^{x1, x2, ...}`. The annotations compose: if `f : (A^{} -> B^{c})^{c}` and `a : A^{}`, then `f(a) : B^{c}`. The universal capability `cap` represents the root of all capabilities — a value of type `T^{cap}` may capture anything.

### Capture-Tracking for Effect Safety

The key insight: if an effect handler binds a capability `k`, and all effect operations require `k` in their capture set, then any code that captures `k` cannot escape the handler. The capture checker enforces this statically, making effect handler scoping a type-system property rather than a runtime mechanism.

### Relationship to Later Work

CF<: was the theoretical foundation for Scala 3's experimental capture checking (`-Ycc`). It was later extended by:
- CCsubBox (Scoped Capabilities, arXiv 2207.03402) — which refined the box mechanism
- System Capless (What's in the Box, OOPSLA 2025) — which added generics support via rcaps

The TOPLAS 2023 publication represents the peer-reviewed journal form of this foundational work.
