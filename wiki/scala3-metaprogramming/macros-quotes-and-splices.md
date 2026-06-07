# Macros: Quotes and Splices

> Sources: Scala 3 Reference — Macros, Unknown
> Raw: [Macros Reference](../../raw/scala3-metaprogramming/2026-06-07-scala3-macros-reference.md)

## Overview

Scala 3 macros are built from two dual operations: **quotation** turns code into a typed value, and **splicing** turns such a value back into code. A macro is just a *top-level splice* evaluated by the compiler, made ergonomic by wrapping it in an [`inline`](inline.md) method.

## Quotes, splices, and the core types

- `'{ expr }` produces an `Expr[T]` (covariant) — code as data.
- `${ e }` splices an `Expr[T]` back into a larger quote (or, at top level inside an `inline def`, runs `e` at compile time).
- `'[ T ]` / `Type.of[T]` produces a `Type[T]` — a type as data.
- A given `Quotes` (`(using Quotes)`) is required to build any quote; each splice introduces a fresh `Quotes`.

Quotes and splices are inverses: `${'{x}} = x` and `'{${e}} = e`.

```scala
def unrolledPowerCode(x: Expr[Double], n: Int)(using Quotes): Expr[Double] =
  if n == 0 then '{ 1.0 } else '{ $x * ${ unrolledPowerCode(x, n-1) } }
```

Generic code needs `Type[T]` context bounds so the type survives erasure across stages:

```scala
def singletonListExpr[T: Type](x: Expr[T])(using Quotes): Expr[List[T]] = '{ List[T]($x) }
```

## The macro skeleton

```scala
inline def powerMacro(x: Double, inline n: Int): Double = ${ powerCode('x, 'n) }
def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] = ...
```

The `inline def` is the public API; the splice calls a **separately-compiled** static method (the macro implementation). Best practice: a top-level splice should be a single call to a compiled method, avoiding nested splices.

## Lifting and unlifting

- **Lift** a value into code with `Expr(v)` (driven by the `ToExpr[T]` type class).
- **Unlift** a constant out of an `Expr` via the `Expr(_)` extractor or `expr.value` / `expr.valueOrAbort` (driven by `FromExpr[T]`).

## The guarantees (Phase Consistency / safety)

- **Hygiene** — identifiers are symbolic references, never accidentally rebound.
- **Well-typedness** — a well-typed quote produces well-typed code; you cannot build `Expr[T]` from a non-`T` expression.
- **Level consistency** — the *staging level* is `(#quotes − #splices)`. A local variable must be used at the same level it was defined; e.g. `${ f('x, n) }` where `n` is a plain (level-0) value used inside a splice is rejected. Global definitions are exempt.
- **Type consistency** — generic types must carry a `Type[T]` to cross stages.
- **Scope extrusion** checks catch quotes whose free variables escape their binding scope.

## Quote pattern matching

You can *deconstruct* code by matching against quoted patterns — the basis of optimizers and DSL rewriters:

```scala
x match
  case '{ power($y, $m) }            => ...        // bind sub-expressions
  case '{ power($y, ${Expr(m)}) }    => ...        // extract a constant m: Int
  case '{ ($ls: List[t]).map[u]($f) } => ...       // type variables t, u (lowercase)
  case '{ $x: t }                    => '{ val y: t = $x; y }
```

Type variables (lowercase names, or `type t` prefixes with bounds) recover precise types despite `Expr`'s covariance. HOAS patterns (`$f(y)`) match under binders. Type patterns `case '[List[t]] =>` inspect a `Type`.

`Expr.summon[T]` performs implicit search *inside* a macro, returning `Option[Expr[T]]` — used to resolve codecs/instances during derivation (e.g. summoning a `Serializer`/`Schema`/`RW` per method parameter).

## Reflection bridge

When quotes/splices and pattern matching are not enough — in particular, to **construct a new class implementing a trait** — you drop down to the `quotes.reflect.*` API via `expr.asTerm` / `term.asExprOf[T]`. See [TASTy Reflection](tasty-reflection.md). This is the level at which nearly all trait-to-implementation derivation libraries operate.

## See Also

- [Inline](inline.md)
- [TASTy Reflection](tasty-reflection.md)
- [Compile-time Operations](compile-time-operations.md)
- [Runtime Staging & TASTy Inspection](runtime-staging-and-tasty-inspection.md)
- [Metaprogramming Overview](metaprogramming-overview.md)
