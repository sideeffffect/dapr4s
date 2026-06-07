# Scala 3 Metaprogramming Overview

> Sources: Scala 3 Reference — Metaprogramming, Unknown
> Raw: [Metaprogramming Index](../../raw/scala3-metaprogramming/2026-06-07-scala3-metaprogramming-index.md)

## Overview

Scala 3 replaces Scala 2's def-macros and macro-annotations with a principled, type-safe metaprogramming framework built on a small set of orthogonal primitives. The whole system is organised as a *spectrum* from purely static inlining to fully runtime code generation, unified by two dual operations — **quotation** (`'{...}`, `'[...]`) and **splicing** (`${...}`). Understanding where each feature sits on that spectrum is the key to reading any Scala 3 derivation library.

## The Six Facilities

1. **`inline`** — A modifier that guarantees a definition is expanded at the use site, during the Typer phase. Inlining is the *driver* for everything else: it enables compile-time conditionals/pattern matching (type-level programming), it is the entry point for macros, and it underpins runtime staging. See [Inline](inline.md).
2. **Compile-time operations** (`scala.compiletime`) — Helpers like `constValue`, `erasedValue`, `summonInline`, `summonFrom`, `error` for computing over values and types at compile time. See [Compile-time Operations](compile-time-operations.md).
3. **Macros** — Quotation converts code to data (`Expr[T]`, `Type[T]`); splicing converts data back to code. Combined with `inline`, this lets you construct program code programmatically and type-safely. See [Macros: Quotes and Splices](macros-quotes-and-splices.md).
4. **Runtime (multi-stage) staging** — The same quotes/splices but *without* `inline`, generating and running code at runtime based on runtime data. See [Runtime Staging & TASTy Inspection](runtime-staging-and-tasty-inspection.md).
5. **Reflection (TASTy reflect)** — A typed-AST API (`quotes.reflect.*`) exposing `Tree`/`Term`/`TypeRepr`/`Symbol`, and crucially the *constructive* side: `Symbol.newClass`, `Symbol.newMethod`, `ClassDef`, `DefDef`. This is what lets a macro **synthesize a brand-new class implementing a trait**. See [TASTy Reflection](tasty-reflection.md).
6. **TASTy inspection** — Loading and analysing `.tasty` files (serialized typed trees) outside of macro expansion.

## The Static-to-Dynamic Spectrum

The single unifying idea: **the staging level of a piece of code = (number of enclosing quotes) − (number of enclosing splices)**.

- A *top-level splice* inside an `inline def` runs the spliced code at **compile time** — this is a macro.
- Balanced quotes/splices is ordinary code.
- Net-positive quotes produces a value (`Expr[T]`) representing code to be generated **later** (runtime staging).

## Why This Matters for Derivation

Every library that "derives an implementation from a trait" (RPC clients, tagless algebras, DI wiring) is a macro that:
1. uses `inline def ... = ${ impl[T] }` as the entry point (facility 1 + 3),
2. reflects over the trait's method symbols via `quotes.reflect` (facility 5),
3. constructs an anonymous class implementing the trait with `Symbol.newClass`/`Symbol.newMethod`/`ClassDef` (facility 5),
4. optionally summons codecs/instances with `Expr.summon` / `summonInline` (facility 2).

See the companion topic [Trait-to-Implementation Derivation Overview](../scala-rpc-derivation/trait-to-impl-derivation-overview.md) for how real libraries apply this.

## Relationship to Scala 2

Scala 2 used `scala.reflect.macros.blackbox/whitebox.Context`, quasiquotes (`q"..."`), and `@compileTimeOnly` macro annotations (macro paradise / `-Ymacro-annotations`). None of these exist in Scala 3; the quotes-and-splices model replaces them and adds static guarantees (hygiene, well-typedness, level/scope consistency). The cross-version gap is what [Scala-Hearth](scala-hearth.md) tries to paper over.

## See Also

- [Inline](inline.md)
- [Compile-time Operations](compile-time-operations.md)
- [Macros: Quotes and Splices](macros-quotes-and-splices.md)
- [TASTy Reflection](tasty-reflection.md)
- [Runtime Staging & TASTy Inspection](runtime-staging-and-tasty-inspection.md)
- [Scala-Hearth](scala-hearth.md)
- [Trait-to-Implementation Derivation Overview](../scala-rpc-derivation/trait-to-impl-derivation-overview.md)
