# Kreuzberg RPC

> Sources: reactivecore/kreuzberg (main, commit db311d8), 2026-06-07
> Raw: [Kreuzberg RPC source](../../raw/scala-rpc-derivation/2026-06-07-kreuzberg-rpc-source.md)

## Overview

Kreuzberg is a Scala.js web framework; its `rpc` module derives **both** sides of an RPC boundary from a shared trait: `Stub.makeStub[T]` builds a client implementation forwarding calls to a `CallingBackend`, and `Dispatcher.makeDispatcher` builds a server router dispatching to a real handler. Cross-platform (JVM + Scala.js), Tier 1, `@experimental`. Both macros share a `TraitAnalyzer` and the [reflect class-synthesis recipe](derivation-mechanism-pattern.md).

## Entry points

```scala
inline def makeStub[T](backend: CallingBackend[Future]): T = ${ makeStubMacro[Future, T]('backend) }   // client
inline def makeDispatcher[A](handler: A): Dispatcher[Future] = ${ makeDispatcherMacro[Future, A]('handler) } // server
```

(`Id`-effect variants `makeIdStub`/`makeIdDispatcher` exist for synchronous use.)

## Mechanism

`TraitAnalyzer.analyze[T]` resolves the trait symbol (taking `.tycon` for applied types), reads an `@ApiName` override, and collects `Flags.Deferred` non-synthetic methods, decoding each `DefDef`'s parameter clauses (tracking `isGiven`/`isImplicit`) and return type.

**Client stub** (`makeStubMacro`): summons `EffectSupport[F]`; declares each method with a rebuilt `MethodType` returning `F[R]` (where `R = returnType.typeArgs.head`); builds `Symbol.newClass("<name>_impl", [Object, A], ...)`. Each body folds args into a `Request` via summoned `ParamEncoder`s, calls `backend.call(apiName, methodName, request)`, and wraps the `F[Response]` with `effect.decodeResponse[R](...)(using ResponseDecoder[R])`.

**Server dispatcher** (`makeDispatcherMacro`): declares `handles`, `call`, and one `call_<method>(input: Request): F[Response]` per method on a class extending `Dispatcher[F]`. `call` checks the service name then walks an `if/else` chain on the method name. Each `call_<method>` decodes parameters via summoned `ParamDecoder`s (bound with `ValDef.let`), re-splits them into the original clause shape, invokes the real handler (`handler.select(method.symbol).appliedToArgss(...)`), and encodes the result with `effect.encodeResponse[R](...)(using ResponseEncoder[R])`, wrapped in a try/catch translating failures.

The two sides are mirror images: stub = encode→call→decode (`ParamEncoder`+`ResponseDecoder`); dispatcher = decode→invoke→encode (`ParamDecoder`+`ResponseEncoder`). Both peel `R` from `F[R]`.

## Caveats

- `@experimental`; every method must return `F[...]` (blindly takes `typeArgs.head`).
- Circe-based codecs must be in scope or the macro aborts.

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Sloth](sloth.md) (also has a server Router)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
