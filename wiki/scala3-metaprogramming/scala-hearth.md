# Scala-Hearth

> Sources: scala-hearth.readthedocs.io, Unknown
> Raw: [Scala-Hearth Overview](../../raw/scala3-metaprogramming/2026-06-07-scala-hearth-overview.md)

## Overview

Scala-Hearth bills itself as "the first Scala macros' standard library." It provides a single, unified macro API that works across **both Scala 2 and Scala 3**, so library authors can write derivation/macro logic once instead of maintaining parallel `scala-2` and `scala-3` source trees. It targets the people who write codecs, transformers, and type-class derivers — exactly the audience building trait/ADT derivation.

## The problem it solves

Scala 2 and Scala 3 have fundamentally different metaprogramming models:
- Scala 2: `scala.reflect.macros.blackbox/whitebox.Context`, quasiquotes (`q"..."`), `c.universe`, macro annotations via macro-paradise / `-Ymacro-annotations`.
- Scala 3: `inline` + `scala.quoted` (`Expr`/`Type`) + `quotes.reflect` (see [Macros: Quotes and Splices](macros-quotes-and-splices.md) and [TASTy Reflection](tasty-reflection.md)).

A library that wants to support both compiler series normally duplicates its macro logic. This is visible in many trait-derivation libraries which keep separate `src/main/scala-2` and `src/main/scala-3` implementations (sloth, automorph, scala-json-rpc, ZIO `IsReloadable`). Hearth aims to remove that duplication.

## How it works

- A **unified macro API** with backing implementations for each Scala version.
- A **"Cross-quotes" compiler plugin** for Scala 3 that lets shared, trait-based macro logic express quotes/splices portably.
- **Version-specific adapter code** bridging the API differences, so the bulk of a derivation lives in shared traits.

## Documentation map

Basic Utilities · Better Printers · Cross Quotes · Micro FP · Standard Extensions · Debug Utilities · Source Utilities · Type Name Utilities · Best Practices · Derivation Checklist · Prior Art & Influences · Resources & Further Reading · FAQ. (All under `scala-hearth.readthedocs.io/en/latest/`.)

## Lineage and relevance

Hearth generalises the cross-version macro infrastructure pioneered by **chimney** (case-class transformations) and **jsoniter-scala** into a reusable toolkit. For the trait-to-implementation derivation landscape it is *infrastructure*, not itself a deriver: it would be the layer a library like [sloth](../scala-rpc-derivation/sloth.md) or [automorph](../scala-rpc-derivation/automorph.md) could adopt to unify their dual Scala-2/Scala-3 macro paths. Its "Derivation Checklist" and "Best Practices" pages are directly applicable to anyone writing the macros documented in the [Trait-to-Implementation Derivation Overview](../scala-rpc-derivation/trait-to-impl-derivation-overview.md).

## See Also

- [Metaprogramming Overview](metaprogramming-overview.md)
- [Macros: Quotes and Splices](macros-quotes-and-splices.md)
- [TASTy Reflection](tasty-reflection.md)
- [Trait-to-Implementation Derivation Overview](../scala-rpc-derivation/trait-to-impl-derivation-overview.md)
