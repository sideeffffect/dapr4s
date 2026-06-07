# tagless-redux

> Sources: goodcover/tagless-redux (master), 2026-06-07
> Raw: [tagless-redux source](../../raw/scala-rpc-derivation/2026-06-07-tagless-redux-source.md)

## Overview

tagless-redux is a reflection-based reimplementation of cats-tagless-style derivation. Its Scala 3 main code derives a `WireProtocol[Alg]` for a tagless-final algebra `Alg[F[_]]` — an RPC-style wire codec: an `encoder: Alg[Encoded]` that serializes each call as `(name, args-tuple)` plus a response decoder, and a `decoder` that reconstructs and dispatches invocations on a real `Alg[F]`. Pluggable backends (Kryo, Pekko, Boopickle). Tier 3; uses the shared [reflect class-synthesis recipe](derivation-mechanism-pattern.md).

## Entry points

```scala
@experimental object MacroKryoWireProtocol:
  inline def derive[Alg[_[_]]](using ScalaKryoSerializer): WireProtocol[Alg] =
    ${ WireProtocolKryoLike.wireProtocol[Alg, KryoImpl, KryoCodec]('KryoCodec) }
// Pekko / Boopickle variants analogous
```

## Mechanism

`wireProtocol` quotes `new WireProtocol[Alg] { def encoder = ${ deriveEncoder }; def decoder = ${ deriveDecoder } }`. Both sides run through the same `DeriveMacros.newClassOf` used by cats-tagless:
- `newClassOf[T]` validates `T` is a trait/abstract class (`is(Flags.Trait) || is(Flags.Abstract)`), creates `$anon` extending `[Object, T]` via `Symbol.newClass`, and uses `overridableMembers` to mint override symbols (`Symbol.newMethod(sym, name, tpe, flags, member.privateIn)`), filtering `Final/Artifact/Synthetic/Mutable/Param` and `Object/Any/AnyRef/AnyVal` owners.
- **Encoder body:** each method serializes `(name, args-tuple)` and carries a response decoder (`Encoded[result]`), summoning the backend codec instances.
- **Decoder side:** rebuilds an `Invocation` per method (casting the wire tuple back and re-applying the original method symbol on a real `Alg[F]`), assembled into a name→fn map.

## Relationship to cats-tagless

README: "basically a straight copy from the cats-tagless project, but done in reflect macros." Scala 2 cats-tagless used Scalameta annotation macros (now effectively dead); tagless-redux instead uses `scala.reflect` blackbox macros on Scala 2 and `scala.quoted`/`quotes.reflect` class synthesis on Scala 3, betting the reflect approach outlives Scalameta. The `DeriveMacros` engine (`transformTo`/`combineTo`/`newClassOf`) is shared with [cats-tagless](cats-tagless.md).

## Caveats

- `@experimental`; `newTypeAlias` uses a Java-reflection hack into `dotty.tools.dotc.core.*` pending Scala 3.6+.
- Backend codecs (Kryo/Pekko/Boopickle) must be in scope.

## See Also

- [cats-tagless](cats-tagless.md)
- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
