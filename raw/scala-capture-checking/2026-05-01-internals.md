# Capture Checking Internals

> Source: https://raw.githubusercontent.com/scala/scala3/main/docs/_docs/reference/experimental/capture-checking/internals.md
> Collected: 2026-05-01
> Published: Unknown

## Core Mechanism

The capture checker functions as a propagation constraint solver operating after type-checking. It introduces constraint variables for inferred type components, method/class references, and constructor parameters.

Explicit capture sets are treated as constants. Subtype requirements between capturing types are checked through subcapturing tests. When comparing `C₁ <: C₂`:
- If the lower set is a variable, `C₂` becomes a recorded superset
- If the upper set is a variable, elements of `C₁` are _propagated_ to `C₂` and onwards through known supersets

## Type Mapping and Approximation

During type transformations, capture sets undergo parallel mapping operations while tracking variance:
- Constant capture sets have their elements mapped as regular types
- Non-capability results get approximated based on variance:
  - Covariant: uses capture sets
  - Contravariant: uses empty sets
  - Nonvariant: creates propagated type ranges

## Capture Tunneling

The implementation addresses capture tunneling through virtual box and unbox operations, inserted similarly to implicit conversions. Boxing "hides a capture set" and unboxing recovers it, controlling propagation boundaries without runtime cost.

## Debugging Support

The `-Ycc-debug` flag reveals the checker's operations, displaying boxed sets and variables with IDs and provenance. Variable identifiers include letters indicating their source:

- `V` — regular variable
- `M` — mapped
- `B` — bijective mapping
- `F` — filtered
- `I` — intersected
- `D` — difference
- `R` — refining class parameters
