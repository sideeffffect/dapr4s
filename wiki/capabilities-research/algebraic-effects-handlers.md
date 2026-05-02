# Algebraic Effects and Handlers

> Sources: Gordon D. Plotkin, Matija Pretnar — ESOP 2009 (conference); LMCS 2013 (journal)
> Raw: [2009-03-01-handlers-of-algebraic-effects](../../raw/capabilities-research/2009-03-01-handlers-of-algebraic-effects.md)

## Overview

Plotkin and Pretnar's "Handlers of Algebraic Effects" (ESOP 2009) is one of the most influential papers in the programming languages research of the past two decades. It establishes the theoretical framework for **algebraic effects and handlers** — a compositional approach to computational effects that separates *what* effects exist (operations with algebraic laws) from *how* they are implemented (handlers).

This paper is the foundational reference for the effect systems that motivate Scala 3's capability-based approach. Understanding it clarifies why capabilities work the way they do, what problems they solve, and how the algebraic laws give effects their composable structure.

## Key Contributions

1. **Effects as Algebraic Operations** — side effects are represented as a signature of named operations with arities and types. This is a purely algebraic (equational) description, independent of any implementation.

2. **Equational Laws** — each effect comes with equations over its operations (e.g., the get-put laws for state). These equations enable equational reasoning about effectful programs without knowing the implementation.

3. **Handlers Generalize Exception Handlers** — a handler provides an operation clause for each effect operation, receiving the operation's argument *and the continuation* (the rest of the computation). This continuation can be invoked 0, 1, or many times.

4. **Denotational Semantics via Free Algebras** — programs with effects denote elements of the free algebra for the effect signature. Handlers are algebra homomorphisms. This gives a clean, compositional semantic model.

5. **Operational Semantics** — an operational (small-step reduction) semantics derived from the denotational one, enabling concrete implementation.

## Core Concepts

### Operations and Signatures

An **effect signature** Σ is a set of operations, each with a type. For example:

| Effect | Operations | Notes |
|---|---|---|
| State `S` | `get : () → S`, `put : S → ()` | Mutable cell |
| Exceptions `E` | `raise : E → ⊥` | No continuation (abort) |
| Nondeterminism | `choose : () → Bool` | Continuation called twice |
| I/O | `read : () → String`, `write : String → ()` | External communication |
| Async/Await | `suspend : () → ()`, `resume : () → ()` | Coroutine scheduling |

The operation signature is *abstract* — it says what operations exist and their types, not how they work.

### Equational Theories (Algebraic Laws)

Each effect has equations that the operations must satisfy:

**State laws:**
- `get; get = get` — reading state twice is the same as reading once (state doesn't change)
- `put(v); put(w) = put(w)` — writing twice is the same as writing once (last write wins)
- `put(v); get = put(v); return v` — after writing `v`, reading gives `v`

These laws hold for *any* correct implementation of state. Programs that rely only on the laws are portable across implementations.

**Nondeterminism laws:**
- `choose; choose = choose` — multiple choices is the same as one (idempotency in sets)
- Commutativity: order of choices doesn't matter for set-based interpretation

The laws define what "state" or "nondeterminism" *means*, independent of how it's implemented.

### Handlers: The Implementation Mechanism

A handler `H` for signature Σ has:
- A **return clause**: `return(v) ↦ e` — what to do when the computation completes normally with value `v`
- **Operation clauses**: for each `op ∈ Σ`, `op(x; k) ↦ e` — what to do when operation `op` is invoked with argument `x`. Here `k` is the **continuation** — the rest of the computation waiting for the operation's result.

Key property: `k` is a first-class function. The handler can:
- Call `k` once with a value — normal/deterministic interpretation
- Not call `k` at all — abort/exception semantics
- Call `k` multiple times — nondeterminism/backtracking
- Call `k` later (store it) — async/cooperative scheduling

### Worked Example: State

```
handler StateH with initial state s0:
  return(v)   ↦ fun s. (v, s)       -- return (result, final state)
  get((); k)  ↦ fun s. k(s)(s)      -- pass current state to continuation, keep state
  put(s'; k)  ↦ fun s. k(())(s')    -- pass unit to continuation, update state
```

`handle(StateH(s0), computation)` transforms an effectful computation into a pure function `State → (Result, State)`. The handler *interprets* the abstract `get`/`put` operations.

### Worked Example: Exceptions

```
handler TryCatch(handle_exn):
  return(v)      ↦ v                 -- normal completion: identity
  raise(e; k)    ↦ handle_exn(e)    -- exception: invoke handler, discard continuation k
```

Exception handlers are a special case where `k` is never called — the continuation is discarded.

### Handlers vs. Monads

Algebraic effects relate to monads through the free algebra construction: every algebraic effect corresponds to a monad. However:

| Monads (transformers) | Algebraic effects + handlers |
|---|---|
| Composition via transformer stacks | Composition via handler nesting |
| Transformer ordering matters | Handler ordering matters (and can be changed) |
| Cannot represent nondeterminism over state cleanly | Nondeterminism + state handled naturally |
| Effect "seeps" into all types | Effect isolated to operations and their handlers |

Handlers are strictly more expressive for some effects (those requiring multi-shot continuations, like nondeterminism or backtracking), and equally expressive for all algebraic effects.

### Not All Effects Are Algebraic

The paper's framework covers effects whose operations are **algebraic** — meaning the equations relate operation sequences without conditions on the results. `callcc` (first-class continuations) is notably *not* algebraic, requiring more general treatment. However, async/await, state, I/O, exceptions, nondeterminism, and most practical effects are algebraic.

## Relevance to Safe Scala / DAPR Project

This paper is the theoretical ancestor of Scala 3's capability-based effects. The lineage:

1. **Plotkin & Pretnar (2009)**: Algebraic effects + handlers — abstract framework
2. **Brachthäuser et al. (2020)**: Effekt language — effects via capabilities in a managed language
3. **Odersky et al. (2021–2022)**: CF<: and CCsubBox — capabilities via capture sets in Scala 3
4. **Safe Scala / DAPR**: DAPR building blocks as typed capabilities in Scala 3

The connection is direct: DAPR building blocks (state store, pub/sub, actors, etc.) are *exactly* algebraic effects:
- **StateStore**: `get` and `set` operations with state laws
- **PubSub**: `publish` and `subscribe` operations
- **ActorRuntime**: actor message dispatch operations

In the capability model, each DAPR building block is a capability value. Using the building block invokes the corresponding effect operation. The "handler" is the DAPR sidecar itself — it interprets these operations against the actual infrastructure.

The Safe Scala library makes this explicit: `withDaprClient { client => ... }` is a handler that scopes the DAPR capability. Inside, operations on `client` correspond to algebraic operations. Outside, the capability is gone — the type system enforces this via capture checking, playing the role of the algebraic effect handler's scope.

## See Also

- [CC Calculus (Capturing Types)](cc-calculus.md) — type-system implementation of scoped effects
- [Scoped Capabilities for Polymorphic Effects](scoped-capabilities-polymorphic-effects.md) — capabilities as the implementation mechanism for algebraic effects
- [Capabilities for Safe Agents](capabilities-for-safe-agents.md) — capabilities applied to AI agent safety
- [Effect Systems Overview](../effect-systems/effect-systems-overview.md) — practical effect system approaches in Scala 3
- [Effekt Capability Passing](../scala-effect-libraries/effekt-capability-passing.md) — Scala library implementing the algebraic effects / capability-passing lineage
