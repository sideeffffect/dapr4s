# Inline

> Sources: Scala 3 Reference — Inline, Unknown
> Raw: [Inline Reference](../../raw/scala3-metaprogramming/2026-06-07-scala3-inline-reference.md)

## Overview

`inline` is a soft modifier that *guarantees* a definition is expanded at the point of use, during the Typer phase (not a backend hint like Scala 2's `@inline`). It is the foundation of Scala 3 metaprogramming: inlining drives compile-time conditionals and pattern matching (type-level programming), serves as the ergonomic entry point to macros, and underpins runtime staging.

## Inline values and methods

- **`inline val`** is a compile-time constant; its RHS must be a constant expression and it gets a literal type (`inline val four = 4` ≡ `inline val four: 4 = 4`). Equivalent to Java `final`.
- **`inline def`** is always inlined at the call site. `inline if`/`inline match` with statically-known conditions reduce to the chosen branch. Inline methods must be fully applied (wildcards like `f(_)` allowed).

```scala
inline def log[T](msg: String, indentMargin: => Int)(op: => T): T =
  if Config.logging then { /* ... */ op } else op
```

When `Config.logging == false`, the whole logging machinery disappears at the call site.

## Inline parameters

A parameter marked `inline` inlines the *actual argument* into the body (by-name evaluation semantics, but allowing duplication). This is how macros receive statically-known arguments (e.g. `inline n: Int`) that the macro body can then pattern-match as a constant.

## Recursive inline and straight-line code

Inline methods may recurse; with constant arguments the recursion unrolls to straight-line code (e.g. `power(x, 10)` becomes a fixed sequence of multiplications). The unrolling depth is bounded by `-Xmax-inlines` (default 32).

## Transparent inline

`transparent inline` lets the *result type* be specialized to something more precise than the declared type, computed during type checking:

```scala
transparent inline def choose(b: Boolean): A = if b then new A else new B
val obj2 = choose(false) // static type B, so obj2.m is callable
```

Key properties:
- Transparent inlines are expanded **during** type checking; plain inlines are expanded **after** typing.
- A `transparent inline given` whose inlining errors is treated as an implicit-search mismatch (search continues) — important for conditional derivation.
- Custom mirrors and many `derives` mechanisms rely on `transparent inline given` so the precise refined type (e.g. the operation tuple) survives to the call site — see [ops-mirror](../scala-rpc-derivation/ops-mirror.md).

## Inline conditionals and matches

- `inline if` requires a constant condition (otherwise compile error); used to statically pick a branch.
- `inline match` selects a case based on compile-time type information; works with ADTs and with `erasedValue[T]`/`constValue[T]` for type-level recursion. See [Compile-time Operations](compile-time-operations.md).

## Overriding rules

1. An inline method may implement a non-inline abstract method (consistent runtime + inlined results).
2. Inline methods are effectively final.
3. Abstract inline methods can only be implemented by inline methods and cannot be invoked through a supertype reference.

## Role in derivation libraries

Every Scala 3 trait-derivation library uses the same skeleton: a user-facing `inline def`/`inline given` whose body is a single top-level splice into a macro:

```scala
inline def wire[T]: T = ${ TraitMacro.impl[T, ...]('self) }   // sloth
inline def derived[A]: DeriveClient[A] = ${ derivedImpl[A] }   // oxygen
transparent inline given reify[T]: Of[T] = ${ reifyImpl[T] }   // ops-mirror
```

## See Also

- [Metaprogramming Overview](metaprogramming-overview.md)
- [Compile-time Operations](compile-time-operations.md)
- [Macros: Quotes and Splices](macros-quotes-and-splices.md)
