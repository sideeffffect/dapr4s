# Handlers of Algebraic Effects
> Source: https://homepages.inf.ed.ac.uk/gdp/publications/Effect_Handlers.pdf
> Collected: 2026-05-01
> Published: 2009-03-22 (ESOP 2009)

## Metadata

**Authors:** Gordon D. Plotkin, Matija Pretnar
**Affiliation:** University of Edinburgh
**Venue:** European Symposium on Programming (ESOP 2009)
**Published in:** Lecture Notes in Computer Science, vol. 5502, Springer
**DOI:** https://doi.org/10.1007/978-3-642-00590-9_7
**Journal version:** "Handling Algebraic Effects" (Logical Methods in Computer Science, 2013)

## Overview

This paper introduces the concept of algebraic effect handlers — one of the most influential ideas in programming language theory of the 2010s–2020s. It provides both the theoretical foundation (algebraic semantics) and practical mechanism (handlers) for modular, composable, first-class effects in programming languages.

## Abstract (reconstructed from paper)

We describe handlers of algebraic effects. Algebraic effects are a class of computational effects whose operations are given by a signature of algebraic operations (with arities), and whose semantics is given by an equational theory over those operations. Handlers provide a means to give semantics to algebraic operations by defining how each operation is to be interpreted; they generalize exception handlers from single "abort" operations to general algebraic operations with continuations. We give a denotational semantics for a language with effects and handlers, and derive an operational semantics from it.

## Key Contributions

1. **Algebraic Effects as Operations + Equations**: An effect is defined by a signature (set of operations with arities) and an equational theory over those operations. For example, state is defined by `get`/`put` operations satisfying equations like `get; get = get` and `put(v); put(w) = put(w)`. This algebraic structure enables modular reasoning.

2. **Handlers Generalize Exception Handlers**: An exception handler catches a single "abort" operation. An algebraic effect handler catches any operation in a signature and provides both a return clause (for normal completion) and operation clauses (for each effect operation). The operation clause receives the operation's argument and a continuation — the rest of the computation after the operation.

3. **Continuations in Handlers**: Unlike monadic bind, handlers expose the continuation explicitly. This means effects can be interpreted in non-standard ways: a handler can invoke the continuation zero times (abort), once (normal), or multiple times (backtracking/nondeterminism). This is the key insight that makes handlers more expressive than monads for some use cases.

4. **Denotational Semantics via Free Algebras**: The denotational model uses the free algebra for the effect signature. Programs with effects denote elements of a free algebra, and handlers are algebra homomorphisms. This gives a clean compositional semantics.

5. **Derived Operational Semantics**: An operational (reduction) semantics is derived from the denotational one, giving a concrete rewriting system for programs with effects and handlers.

## Core Concepts

### Operations and Signatures

An effect signature Σ consists of operation symbols, each with an arity (the type of its argument and the type of its continuation result). For example:
- State: `get : () → S`, `put : S → ()`
- Exceptions: `raise : E → ⊥` (arity zero for the continuation — no resumption)
- Nondeterminism: `choose : () → Bool` (arity two — continuation called with true or false)

### Equational Theories

Operations satisfy equations that constitute the "laws" of the effect:
- State: `do x ← get; do _ ← put(x); k = k` (get-put law)
- State: `do _ ← put(v); do x ← get; k(x) = do _ ← put(v); k(v)` (put-get law)
- Nondeterminism: commutativity, idempotency, etc.

These equations allow programs to be equationally reasoned about independent of how effects are implemented.

### Handlers

A handler `H` for signature Σ consists of:
- A **return clause**: how to handle a pure value (no more effects)
- **Operation clauses**: for each op `op ∈ Σ`, a clause `op(x; k) ↦ e` defining what to do when `op` is invoked with argument `x` and continuation `k`

The handler wraps a computation: `handle(H, M)` runs `M` until it invokes an effect, then dispatches to the appropriate clause. The continuation `k` represents "the rest of M after this operation."

### Example: State Handler

```
handler StateH(init):
  return(v) ↦ λs. (v, s)
  get((); k) ↦ λs. k(s)(s)        -- resume with current state, unchanged
  put(s'; k) ↦ λs. k(())(s')      -- resume with unit, update state to s'
```

Running `handle(StateH(s0), computation)` gives a function from initial state to `(result, final_state)`.

### Relationship to Monads

Every algebraic effect corresponds to a monad (via the free algebra construction). However, handlers are more compositional than monad transformers: handlers can be combined in any order without the ordering constraints of transformer stacks. Effects that are not algebraic (e.g., callcc) require more general "continuation" effects.

## Importance to Scala / Effect Systems

This paper is the theoretical foundation for:
- **Effekt** language (Brachthäuser et al.) — direct application
- **Koka** language (Leijen) — effect inference system
- **Scala capability-based effects** — scoped capabilities as a type-safe implementation of the handler pattern
- The general idea that effects should be *first-class*, *named*, and *composable* — which motivates capability-based approaches in Scala 3

The key insight adopted in Scala's design: capabilities play the role of "tokens that permit invoking effect operations," and scoping the capability enforces the handler's scope. This avoids the need for a separate effect layer in the type system.
