# distage TraitConstructor (izumi)

> Sources: 7mind/izumi (develop, HEAD 5419992c82dc), 2026-06-07
> Raw: [distage TraitConstructor source](../../raw/scala-rpc-derivation/2026-06-07-distage-traitconstructor-source.md)

## Overview

distage (part of izumi) is a staged, compile-time dependency-injection framework. Its `TraitConstructor[T]` macro **auto-implements an abstract trait/class**: it synthesizes a concrete subclass whose abstract parameterless `def`s/`val`s are filled with injected dependencies, packaged as a `Functoid[T]` for the DI planner. Not RPC, but the identical trait-to-impl-via-Scala-3-macro mechanism. Tier 3.

## Entry point

```scala
object TraitConstructor:
  inline implicit def materialize[T]: TraitConstructor[T] = ${ TraitConstructorMacro.make[T] }
```

## Mechanism

`make[T]` builds a `ConstructorUtil` + `ConstructorContext` (computing parent types, constructor param lists, and `methodDecls` — the abstract members to implement). `implementTraitAutoImplBody`:
1. Builds parent constructor call terms from the leading lambda args.
2. Creates the class symbol `<Name>TraitAutoImpl` via `Symbol.newClass(..., decls = generateDeclSymbols(forceLazyVals = true), ...)` — each abstract member becomes an overriding **lazy val**.
3. Binds each member's field to its injected by-name dependency: `ValDef(clsSym.declaredField(name), Some(methodImpl))`.
4. `ClassDef` + `New`, wrapped in `TraitConstructor.wrapInitialization[R](...)` for nicer error messages, ascribed to `R`.
5. The whole thing is reflected into a `Functoid[R]` (a `Seq[Any] => R` carrying each dependency's `Tag`) so distage's planner can resolve and inject positionally.

## The reflection shim

`Symbol.newClass` (with a custom constructor) and `ClassDef.apply(sym, parents, body)` were experimental/private API at the time, so distage calls them through a `java.lang.reflect` shim (`ReflectiveCall.call[Out](Symbol, "newClass", ...)`) to bypass the compile-time access check — an instructive workaround documented in [TASTy Reflection](../scala3-metaprogramming/tasty-reflection.md)'s caveats.

## Why it's in scope

It reads a trait's abstract members and synthesizes a concrete implementing subclass at compile time — the same `Symbol.newClass` + `ClassDef` machinery as every RPC-client deriver, applied to DI wiring instead of remote calls.

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [TASTy Reflection](../scala3-metaprogramming/tasty-reflection.md) (the reflection-shim caveat)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
