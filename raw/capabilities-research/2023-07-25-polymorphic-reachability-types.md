# Polymorphic Reachability Types
> Source: https://arxiv.org/abs/2307.13844
> Collected: 2026-05-01
> Published: 2023-07-25

## Metadata

**Authors:** Guannan Wei, Oliver Bračevac, Songlin Jia, Yuyan Bao, Tiark Rompf
**Affiliation:** Purdue University; EPFL
**arXiv:** https://arxiv.org/abs/2307.13844
**DOI:** https://doi.org/10.48550/arXiv.2307.13844
**License:** CC BY 4.0
**Category:** cs.PL
**Coq formalization:** included

## Abstract

Reachability types are a recent proposal that has shown promise in scaling to higher-order but monomorphic settings, tracking aliasing and separation on top of a substrate inspired by separation logic. The prior λ* reachability type system qualifies types with sets of reachable variables and guarantees separation if two terms have disjoint qualifiers. However, naive extensions with type polymorphism and/or precise reachability polymorphism are unsound, making λ* unsuitable for adoption in real languages. Combining reachability and type polymorphism that is precise, sound, and parametric remains an open challenge. This paper presents a rethinking of the design of reachability tracking and proposes a solution to the key challenge of reachability polymorphism. Instead of always tracking the transitive closure of reachable variables as in the original design, we only track variables reachable in a single step and compute transitive closures only when necessary, thus preserving chains of reachability over known variables that can be refined using substitution. To enable this property, we introduce a new freshness qualifier, which indicates variables whose reachability sets may grow during evaluation steps. These ideas yield the simply-typed λ◇-calculus with precise lightweight, i.e., quantifier-free, reachability polymorphism, and the F<:◇-calculus with bounded parametric polymorphism over types and reachability qualifiers. We prove type soundness and a preservation of separation property in Coq.

## Key Contributions

1. **Single-Step Reachability Tracking**: Instead of computing transitive closures of reachability eagerly, the system tracks only direct (single-step) reachability. Transitive closures are computed lazily when needed. This design choice is what makes polymorphism sound: substitution can refine reachability chains, whereas transitive closures collapse the information needed for refinement.

2. **Freshness Qualifier (◇)**: A new qualifier indicating that a variable's reachability set may expand during evaluation (i.e., the variable is "fresh" and may accumulate new references as the computation proceeds). This is essential for handling allocation and other operations that grow reachability.

3. **λ◇-Calculus**: A simply-typed calculus with lightweight, quantifier-free reachability polymorphism. Reachability polymorphism is achieved without explicit quantifiers — the system infers how reachability sets propagate through higher-order functions.

4. **F<:◇-Calculus**: An extension adding bounded parametric polymorphism over both types and reachability qualifiers. This is the full system needed for generic programming: both type parameters and their reachability properties can be abstracted and bounded.

5. **Separation Preservation**: Formal proof that if two terms have disjoint qualifiers (reachability sets), they remain disjoint throughout evaluation. This is the key safety property: separation cannot be accidentally violated by polymorphic code.

6. **Coq Formalization**: Type soundness (progress and preservation) and the separation preservation theorem are all proved in Coq.

## Core Concepts

### Reachability Qualifiers

Every type is annotated with a qualifier — a set of variables from the enclosing scope that the value can transitively reach (i.e., whose resources it can access or alias). The qualifier `∅` means the value has no external references. The qualifier `{x}` means the value may alias or contain references reachable from `x`.

### Aliasing and Separation

Two terms `e1 : T^{q1}` and `e2 : T^{q2}` are **separated** if `q1 ∩ q2 = ∅`. Separation is a stronger property than the usual distinction between values: it guarantees they share no resources, enabling safe parallel evaluation or independent reasoning.

### Why Naive Polymorphism Is Unsound in λ*

In λ*, qualifiers track transitive closure. When you substitute a type variable `T` with a concrete type, the transitive reachability through `T` must be recomputed — but the original collapsed transitive closure doesn't preserve enough information to do this correctly. The paper gives concrete examples of unsoundness in prior approaches.

### Single-Step + Freshness as the Fix

By tracking only direct reachability `x ↦ {y, z}` ("`x` directly holds `y` and `z`"), substitution works correctly: substituting `x` for a parameter refines the reachability chain naturally. The freshness qualifier `◇` handles the case where a variable's set grows: fresh variables are treated conservatively until their reachability stabilizes.

### Relationship to Capture Checking

Reachability types and capture checking (CF<:, CCsubBox) address overlapping concerns — both track what a value "holds" from the ambient scope. The key differences:
- Reachability types focus on aliasing and separation (separation logic heritage)
- Capture checking focuses on effect scoping and capability confinement
- This paper's F<:◇ is more directly comparable to CCsubBox in expressiveness
- Both lines of work converge on tracking "free variables in types"
