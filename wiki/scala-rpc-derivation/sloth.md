# Sloth

> Sources: cornerman/sloth (master), 2026-06-07
> Raw: [Sloth macro source](../../raw/scala-rpc-derivation/2026-06-07-sloth-scala3-macro-source.md)

## Overview

Sloth is a minimal, transport-agnostic RPC library. The user defines a service trait whose methods return `Result[_]` (e.g. `Future`), and `client.wire[T]` derives a client implementation; a matching `Router` handles the server side. It is the cleanest reference implementation of the [reflect class-synthesis recipe](derivation-mechanism-pattern.md). Tier 1, `@experimental`. (A separate Scala 2 macro path exists under `src/main/scala-2`; only the Scala 3 path is in scope here.)

## Entry point

```scala
inline def wire[T]: T = ${ TraitMacro.impl[T, PickleType, Result]('self) }
```

`wire` is mixed into `ClientCo`/`ClientContra` via `PlatformSpecificClient`. `self` (the client carrying the `RequestTransport`) is passed into the macro.

## Mechanism

Follows the standard recipe (steps 1–5 in [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)):
1. `definedMethodsInType[Trait]` keeps `Flags.Deferred`, non-private/protected, non-constructor, non-synthetic methods.
2. `Symbol.newMethod(cls, name, TypeRepr.of[Trait].memberType(method), ...)` per method.
3. `Symbol.newClass(spliceOwner, "Anon", [Object, Trait], decls, None)`.
4. **The Sloth-specific body:** the impl instance `new ClientImpl(prefix)` is bound once with `ValDef.let`; each method packs its arguments (`Nil → ()`, single → the arg, many → `Expr.ofTupleFromSeq`), computes the matching tuple type and the inner return type (peeling `Result[_]`), and emits `implRef.execute[TupleType, ReturnType](Method(traitName, methodName), packedArgs)`.
5. `ClassDef` + `New` + `Typed(..., Trait)`.

At runtime `ClientImpl.execute` serializes the packed args (`Serializer`), wraps them in a `Request(Method(trait, method), bytes)`, runs the `RequestTransport`, and deserializes the response into `Result[R]` (`Deserializer`), routing failures through a `ClientHandler`.

## Notable details

- Method path = `Method(traitName, methodName)`, both overridable via `@sloth.Name`.
- Two flavors: **Co** (`ClientImpl`, needs `Serializer[args]` + `Deserializer[result]`) and **Contra** (`ClientContraImpl`, serializes the result type instead).
- Compile-time checks (`checkMethodErrors`): rejects overloaded methods, per-method generic type parameters, and return types whose constructor doesn't conform to `Result[_]`.

## Caveats

- `@experimental` — callers need the experimental flag.
- Serializer/Deserializer typeclass instances are resolved at the generated call site, so missing codecs fail there.

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Automorph](automorph.md) (contrast: Proxy instead of class synthesis)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
