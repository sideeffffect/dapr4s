# cats-tagless

> Sources: typelevel/cats-tagless (master), 2026-06-07
> Raw: [cats-tagless source](../../raw/scala-rpc-derivation/2026-06-07-cats-tagless-scala3-source.md)

## Overview

cats-tagless derives implementations of tagless-final algebra traits `Alg[F[_]]`. The flagship is `derives FunctorK`, which produces a `FunctorK[Alg]` whose `mapK` turns an `Alg[F]` into an `Alg[G]` given a natural transformation `F ~> G`. It is not network RPC, but it uses **exactly the same** [reflect class-synthesis recipe](derivation-mechanism-pattern.md) — the "transport" in step 4 is just delegation + applying the transformation. Tier 3, `@experimental`. The shared `DeriveMacros` engine also powers ApplyK/ContravariantK/InvariantK/SemigroupalK, instrumentation, and `Aspect`.

## Entry point

```scala
trait DerivedFunctorK:
  @experimental inline def derived[Alg[_[_]]]: FunctorK[Alg] = summonFrom:
    case derived: Derived[FunctorK[Alg]] => derived.instance
    case _ => Derive.functorK[Alg]   // -> MacroFunctorK.derive
```

`object FunctorK extends DerivedFunctorK`, so `trait Alg[F[_]] derives FunctorK` resolves to this `derived`.

## Mechanism

`MacroFunctorK.functorK` quotes `new FunctorK[Alg]:  def mapK[F,G](alg: Alg[F])(fk: F ~> G): Alg[G] = ${ deriveMapK('alg, 'fk) }`. `deriveMapK` calls the generic `DeriveMacros` engine:
- `newClassOf[Alg[G]]` builds an anonymous `$anon` class extending `Alg[G]` (`Symbol.newClass` + parents `[Object, Alg[G]]`).
- `overridableMembers` enumerates the algebra's methods/vals/types, filters non-overridable, and mints fresh override symbols (`Symbol.newMethod`/`newVal`/`newTypeAlias`) substituting the type member `F → G`.
- **Step-4 body:** each method delegates to the original (`alg.method(args)` via `term.call(sym)(argss)`), then the result is post-processed: where the (substituted) return type contains `G`, a `FunctorK` is summoned and `.mapK(...)(fk)` applied — for a leaf `F[A]` return this is just `fk(alg.method(args))`. Arguments contravariant in `G` are handled with a summoned `ContravariantK` + `contramapK`.
- `ClassDef` + `New` complete the `Alg[G]`.

## Why it's in scope

It is genuine trait-to-implementation synthesis: the macro reflectively reads a user algebra trait and emits a fresh class implementing it — the same machinery an RPC proxy uses. It's the most mature Scala 3 example of the technique. ([tagless-redux](tagless-redux.md) is a reflect-macro reimplementation of the same idea, used for RPC wire codecs.)

## Caveats

- `@experimental`; `newTypeAlias` uses a reflective hack pending Scala 3.6+'s `Symbol.newTypeAlias`.
- Scala 2 cats-tagless uses Scalameta/`@autoFunctorK` annotation macros — out of scope; only the Scala 3 `derives` path qualifies.

## See Also

- [tagless-redux](tagless-redux.md)
- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
