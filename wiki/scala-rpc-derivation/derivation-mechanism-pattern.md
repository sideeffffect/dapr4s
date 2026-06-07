# Derivation Mechanism Pattern (the shared recipe)

> Sources: Source-code survey of qualifying libraries, 2026-06-07
> Raw: [Sloth](../../raw/scala-rpc-derivation/2026-06-07-sloth-scala3-macro-source.md); [Oxygen](../../raw/scala-rpc-derivation/2026-06-07-oxygen-deriveclient-source.md); [Spice](../../raw/scala-rpc-derivation/2026-06-07-spice-apiclientmacro-source.md); [Kreuzberg](../../raw/scala-rpc-derivation/2026-06-07-kreuzberg-rpc-source.md); [cats-tagless](../../raw/scala-rpc-derivation/2026-06-07-cats-tagless-scala3-source.md)

## Overview

Almost every Scala 3 library that derives an implementation from a trait via `quotes.reflect` follows the *same* five-step recipe. Once you recognise it, every per-library article is just a variation on step 4 (the generated method body). This article distills the canonical pattern; the library articles point back here and only describe their differences.

## The five steps

```scala
import quotes.reflect.*
val tpe = TypeRepr.of[T]

// 1. COLLECT the trait's abstract methods (the ones to implement)
val methods = tpe.typeSymbol.methodMembers.filter(_.flags.is(Flags.Deferred))
  // also filter out: constructors, Synthetic, Private/Protected, Object/Any-owned, $default$ accessors

// 2. DECLARE overriding method symbols for the new class.
//    Use tpe.memberType(m) — NOT m's own type — so the trait's type params resolve.
def decls(cls: Symbol): List[Symbol] = methods.map { m =>
  Symbol.newMethod(cls, m.name, tpe.memberType(m), Flags.Override, Symbol.noSymbol)
}

// 3. CREATE the class symbol extending [Object, T]
val parents = List(TypeTree.of[Object], TypeTree.of[T])
val cls = Symbol.newClass(Symbol.spliceOwner, "Anon", parents.map(_.tpe), decls, selfType = None)

// 4. GENERATE each method body: DefDef(sym, argss => Some(rhs))
val body = cls.declaredMethods.map { m =>
  DefDef(m, argss => Some( /* THE LIBRARY-SPECIFIC PART */ ))
}

// 5. DEFINE the class and INSTANTIATE it, ascribed to T
val clsDef = ClassDef(cls, parents, body)
val instance = Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[T])
Block(List(clsDef), instance).asExprOf[T]
```

The whole thing is reached through an `inline def`/`inline given` entry point: `inline def derived[T]: F[T] = ${ impl[T] }`. See [Inline](../scala3-metaprogramming/inline.md) and [TASTy Reflection](../scala3-metaprogramming/tasty-reflection.md).

## What varies — step 4, the method body

This is the *only* substantive difference between the libraries:

| Library | Generated body does … |
|---|---|
| Sloth | pack args into Unit/value/tuple → `impl.execute[Tup, R](Method(trait, method), args)` (serialize + transport) |
| Oxygen | fold params into route `In` → `endpointImpl.send(in, client)` (HTTP via zio Client) |
| Spice | dispatch by shape → `ApiClientRuntime.doGet/doRestful/doJson(...)` (HTTP, fabric.rw codecs) |
| Kreuzberg (stub) | `ParamEncoder.encode` into `Request` → `backend.call(api, name, req)` → `decodeResponse[R]` |
| Kreuzberg (dispatcher) | `ParamDecoder.decode` from `Request` → invoke real handler → `encodeResponse[R]` |
| cats-tagless | delegate to original `alg.method(args)` then apply `fk: F ~> G` to the result |
| ZIO IsReloadable | `scopedRef.get.flatMap(svc => svc.method(args))` |
| distage | each member is an overriding **lazy val** bound to an injected by-name dependency |

## Recurring sub-techniques

- **Codec/instance summoning per method:** `Expr.summon[Serializer[T]]` / `Expr.summon[Schema[T]]` / `Expr.summon[RW[T]]`, aborting with `report.errorAndAbort` when missing — so missing codecs are *compile* errors. See [Compile-time Operations](../scala3-metaprogramming/compile-time-operations.md).
- **Binding the transport once:** `ValDef.let(spliceOwner, implInstance)(implRef => ...)` introduces a single val every generated method references (Sloth), or a per-endpoint `lazy val` (Oxygen `withImpl`).
- **Reconstructing parameter lists:** `MethodType(names)(_ => types, _ => result)` folded over curried/implicit/given clauses; bodies use `argss.flatten` and `appliedToArgss`.
- **Peeling the effect:** for `def m(...): F[R]`, take `returnType.typeArgs.head` to recover `R` for the response codec (Kreuzberg, tagless-redux).
- **Ownership:** spliced bodies may need `.changeOwner(methodSym)`.

## Two notable deviations from the recipe

- **Automorph** does *not* synthesize a class at all — the macro emits a `Seq[ClientBinding]` and the runtime instance is a `java.lang.reflect.Proxy` dispatched by method name. See [Automorph](automorph.md).
- **ops-mirror / smithy4s-deriving** split the work: a macro first reifies the trait into a typelevel *operation mirror*, and a *separate* consumer macro/derivation does the step-3/4 synthesis. See [ops-mirror](ops-mirror.md).

## Caveats baked into the pattern

- Most implementations are `@experimental` because `Symbol.newClass` / class synthesis is experimental API; some (distage, tagless-redux) call it via a Java-reflection shim, or have a workaround for the missing `Symbol.newTypeAlias` pre-3.6.
- Common compile-time restrictions: no overloaded methods (name collisions), no per-method generic type params, no curried methods, return type must match the expected effect constructor.

## See Also

- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
- [TASTy Reflection](../scala3-metaprogramming/tasty-reflection.md)
- [Macros: Quotes and Splices](../scala3-metaprogramming/macros-quotes-and-splices.md)
