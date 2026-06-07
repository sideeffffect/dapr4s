# Compile-time Operations

> Sources: Scala 3 Reference — Compile-time operations, Unknown
> Raw: [Compile-time Ops Reference](../../raw/scala3-metaprogramming/2026-06-07-scala3-compiletime-ops-reference.md)

## Overview

The `scala.compiletime` package provides primitives that, combined with [`inline`](inline.md), let you compute over values and types at compile time **without** writing a full quotes/splices macro. These are the workhorses of type-class derivation in the `Mirror`/`derives` style.

## Value/type bridges

- **`constValue[T]` / `constValueOpt[T]`** — materialize the constant value denoted by a (singleton) type, or a compile error / `None`. `constValueTuple[(X1,…,Xn)]` produces the tuple of constants.
- **`erasedValue[T]`** — an `erased` (no runtime) pseudo-value used only to drive an `inline match` on a type. The canonical idiom for type-directed recursion:

```scala
transparent inline def defaultValue[T] =
  inline erasedValue[T] match
    case _: Int     => Some(0)
    case _: Boolean => Some(false)
    case _          => None
```

- **`scala.compiletime.ops.*`** — type-level operations on singleton types (`int.*`, `boolean.&&`, `string.+`), evaluated by the compiler when all operands are singletons: `val multiplication: 3 * 5 = 15`.

## Summoning instances

- **`summonInline[T]`** — a delayed `summon`/`implicitly` that triggers `@implicitNotFound` messages; used inside `inline match` branches so the implicit is only required on the path actually taken.
- **`summonFrom { case ... }`** — first-match implicit search within a function block; supports both bound values and `case given Ordering[T] => ...` patterns. Used to branch derivation on the availability of an instance:

```scala
inline def setFor[T]: Set[T] = summonFrom {
  case ord: Ordering[T] => new TreeSet[T]()(using ord)
  case _                => new HashSet[T]
}
```

- **`summonAll[T <: Tuple]`** — summon a whole tuple of instances (used to collect element type-class instances during ADT derivation).

## error / codeOf

`error(inline msg)` aborts compilation with a custom message; `codeOf(x)` renders an expression's source for diagnostics:

```scala
inline def fail(inline p1: Any) = error("failed on: " + codeOf(p1))
```

## Relationship to Mirror-based derivation

`scala.compiletime` + `scala.deriving.Mirror` is the standard, macro-free way to derive type-class instances for **ADTs** (case classes / enums): you read `Mirror.ProductOf[T]#MirroredElemTypes`, recurse with `erasedValue`/`summonInline`, and assemble an instance. This is how circe-derivation, zio-schema-style derivers, and many `derives` clauses work.

Crucially, stock `Mirror` only reflects **algebraic data types, not method-bearing traits** — so it cannot, by itself, derive an *implementation of a service trait*. That gap is exactly why operation-mirror libraries (see [ops-mirror](../scala-rpc-derivation/ops-mirror.md)) build a custom mirror via [reflection](tasty-reflection.md) and then expose it for `derives`-style consumption. See [Trait-to-Implementation Derivation Overview](../scala-rpc-derivation/trait-to-impl-derivation-overview.md) for the full picture.

## See Also

- [Inline](inline.md)
- [Macros: Quotes and Splices](macros-quotes-and-splices.md)
- [Metaprogramming Overview](metaprogramming-overview.md)
