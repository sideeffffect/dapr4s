# Polymorphic Reachability Types

> Sources: Guannan Wei, Oliver Bračevac, Songlin Jia, Yuyan Bao, Tiark Rompf — arXiv 2023
> Raw: [2023-07-25-polymorphic-reachability-types](../../raw/capabilities-research/2023-07-25-polymorphic-reachability-types.md)

## Overview

This paper (arXiv 2023, Purdue/EPFL) addresses a fundamental challenge at the intersection of two type-theoretic ideas: **reachability types** (which track what a value can alias) and **parametric polymorphism** (generics). Prior reachability type systems (λ*) were sound only for monomorphic programs. The paper proves that naive additions of polymorphism to λ* are *unsound*, then presents a redesigned system — the **λ◇-calculus** and **F<:◇-calculus** — that is both sound and handles the full combination of generics and reachability tracking.

Reachability types are a parallel lineage to Scala's capture checking. While capture checking focuses on capability confinement and effect scoping, reachability types focus on *aliasing and separation* — knowing when two references are guaranteed to point to disjoint parts of the heap. Both systems track "what a value holds," and their convergence is a notable theme in recent PL research.

## Key Contributions

1. **Soundness Counterexamples** — concrete demonstrations that naive polymorphism extensions to λ* (the original reachability type system) are unsound, motivating the redesign.

2. **Single-Step Reachability** — the key design choice: track only *direct* (one-step) reachability, not transitive closure. Transitive closures are computed lazily when needed. This preserves the information structure that makes substitution correct under polymorphism.

3. **Freshness Qualifier (◇)** — a new qualifier indicating variables whose reachability sets may grow during evaluation. Fresh variables are treated conservatively and are essential for soundly handling allocation and growing data structures.

4. **λ◇-Calculus** — simply-typed calculus with quantifier-free reachability polymorphism. Polymorphism over reachability is implicit and lightweight — no explicit quantifiers needed.

5. **F<:◇-Calculus** — extends λ◇ with bounded parametric polymorphism over both *types* and *reachability qualifiers*. This is the full generic system.

6. **Coq Proofs** — type soundness (progress + preservation) and preservation of separation, all mechanized in Coq.

## Core Concepts

### Reachability Qualifiers

Every type is annotated with a qualifier — a set of variables from the enclosing scope that the value can (transitively) reach:

```
x : Int^{∅}        -- x holds an integer with no external references
f : (A → B)^{r}    -- f is a function that may alias resources reachable from r
p : Pair^{a, b}    -- p is a pair that may alias both a and b
```

A qualifier `{x}` means: "following the references in this value, you can reach the same resources as `x`." Empty qualifier `∅` means the value is self-contained.

### Separation

Two terms `e1 : T^{q1}` and `e2 : T^{q2}` are **separated** when `q1 ∩ q2 = ∅`. Separation guarantees they share no heap resources — safe to evaluate in parallel, or to reason about independently. This is the reachability types' analogue of separation logic's `*` (separating conjunction).

### Why Transitive Closure Breaks Polymorphism

In λ* (the original system), qualifiers track *transitive* reachability: if `x` reaches `y` and `y` reaches `z`, then `{x}` includes `z`. This collapses the chain.

Problem with generics: when you substitute a type variable `T[q := r]`, you need to substitute `q` in the qualifier. But if `q`'s reachability has been transitively closed through unknown types, the substitution cannot be performed correctly — you don't know what was transitively included. This is the source of unsoundness.

### Single-Step Reachability as the Fix

λ◇ tracks only direct one-step reachability: `x ↦ {y}` means "`x` directly holds a reference to `y`" — not what `y` transitively reaches. When you need the transitive closure, you compute it from the chain of single-step facts.

Under substitution `[T := S]`, a chain `x ↦ {T} ↦ {y}` becomes `x ↦ {S} ↦ {y}` — the chain is refined, not collapsed. This makes substitution correct.

### Freshness Qualifier (◇)

A variable `x : T^◇` has a *fresh* qualifier — its reachability set may grow during evaluation (for example, as elements are added to a list, or as an object accumulates references). Fresh variables are treated conservatively in the separation reasoning: you cannot conclude that a fresh variable is separated from anything until its reachability stabilizes.

### λ◇: Quantifier-Free Reachability Polymorphism

In λ◇, reachability polymorphism is *implicit* — there are no explicit `∀q.` quantifiers in the surface syntax. When a function `f : (A^q → B^q)` is applied to arguments with different qualifiers, the system automatically infers the appropriate instantiation.

### F<:◇: Full Bounded Parametric Polymorphism

F<:◇ adds:
- **Type parameters with bounds**: `[T <: U]` as in System F<:
- **Reachability parameters with bounds**: `[q <: r]` — the qualifier `q` is bounded above by qualifier `r`
- Combined bounds: `[T <: U^q]` — both the type and its qualifier are bounded

This is sufficient to write fully generic, polymorphic code with precise separation reasoning.

## Relationship to Capture Checking

| Reachability Types | Capture Checking (Scala 3) |
|---|---|
| Qualifier = set of reachable variables | Capture set = set of captured variables |
| Separation: disjoint qualifiers | Confinement: capability doesn't escape scope |
| Freshness qualifier for growing refs | Box type for hidden capture sets |
| Focus: aliasing, sharing, data races | Focus: effects, resources, scope safety |
| λ* / λ◇ / F<:◇ calculi | CF<: / CCsubBox / System Capless |

Both systems track "what a value holds from the surrounding environment." The differences are emphasis and application domain:
- Reachability types are more natural for heap aliasing and data race reasoning
- Capture checking is more natural for effect systems and resource lifetimes

Oliver Bračevac is a co-author on both lines of work, reflecting the deliberate cross-pollination between them.

## Relevance to Safe Scala / DAPR Project

Polymorphic reachability types are not directly used in Scala 3's current implementation, but they inform the theoretical landscape:

- **Aliasing safety**: When a DAPR `StateStore^` capability is passed to multiple actors, understanding which actors share vs. own separate state requires aliasing reasoning. F<:◇ provides the theoretical framework for this.
- **Data race prevention**: The related paper "Degrees of Separation" (Boruch-Gruszecki 2023) applies similar ideas specifically to data race prevention — directly applicable to concurrent DAPR actors.
- **Future Scala evolution**: As Scala's capture checking matures, reachability-style separation reasoning may be incorporated. Understanding this paper prepares for that evolution.
- **Generic library design**: The soundness results in F<:◇ provide confidence that capture-polymorphic generic APIs (like those in the Safe Scala library) can be designed correctly.

## See Also

- [CC Calculus (Capturing Types)](cc-calculus.md) — parallel capture-based approach
- [Scoped Capabilities for Polymorphic Effects](scoped-capabilities-polymorphic-effects.md) — CCsubBox and effect polymorphism
- [Reach Capabilities](reach-capabilities.md) — converging solution for generics in capture checking
