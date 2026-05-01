# Scoped Capabilities

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/scoped-capabilities.md
> Collected: 2026-05-01
> Published: Unknown

## Overview

Scoped capabilities control resource management and prevent capability escape. The system introduces `any` — a capability that varies based on context — alongside `fresh` for creating isolated, existentially-bound capabilities.

## Four Types of `any`/`fresh`

1. **Local `any`s** — Each class, method, and block has its own local `any`, forming a hierarchy based on lexical nesting where inner scopes subsume outer ones.

2. **Parameter `any`s** — Function parameters get scoped `any`s that instantiate to actual capabilities at call sites.

3. **Result `any`s** — In function return types, `any` refers to the enclosing scope's local `any`, meaning multiple calls share the same capture-set bound.

4. **Result `fresh`s** — Unlike `any`, `fresh` creates existentially-bound, isolated capabilities where "each call yields a result capturing a fresh, distinct capability."

## Level Hierarchy

Capabilities flow according to a level system where:
- Outer scopes contain inner scopes
- Capabilities can flow inward (deeper nesting) but not outward
- This prevents resources from escaping their valid lifetime

Example — assigning a scoped capability to a wider-scoped variable is rejected:

```scala
var esc: File^/*{any₁}*/ = null
withFile("test.txt"): f /* : File^{any₂} */ =>
  esc = f   // Error: any₂ cannot flow into any₁
```

## Expansion Rules for Function Types

`fresh` in a function result is existentially bound: `() -> Ref^{fresh}` means `() -> ∃fresh. Ref^{fresh}`. Each call creates a new, distinct capability.

## Rust Comparison

Parallels with Rust's lifetime system:
- Capability names ≈ Lifetime parameters
- Capture sets ≈ Lifetime bounds
- Level containment ≈ Outlives relation

Scala computes levels automatically from program structure rather than requiring explicit parameters.
