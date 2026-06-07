# Trait-to-Implementation Derivation Overview

> Sources: Multi-source survey, 2026-06-07
> Raw: [Landscape Survey](../../raw/scala-rpc-derivation/2026-06-07-landscape-survey.md)

## Overview

A recurring Scala pattern: a user describes a service as a **trait of method signatures**, and a library **automatically synthesizes an implementation** of that trait. The classic case is an RPC/HTTP **client** — each generated method serializes its arguments, performs a remote call, and deserializes the response — but the same machinery also produces servers/routers, transformed tagless-final algebras, hot-reload proxies, and dependency-injection-wired instances. This topic catalogues the Scala 3 libraries that do this and, in companion articles, exactly *how* each one's macro works. It is directly relevant to dapr4s, which faces the same "derive a typed client/binding from a service trait" problem.

## The filter

A library qualifies only if it (a) genuinely derives an *implementation from a trait* — not codegen of stubs from an external IDL/protobuf, and not requiring a hand-written impl — AND (b) does so with a **Scala 3** mechanism: Scala 3 macros (`inline` + `scala.quoted` + `quotes.reflect`) **or** Scala 3 Mirror/typeclass derivation. Excluded: Scala 2 macros, pure runtime reflection, and external/explicit code generation.

## Mechanism taxonomy

In practice every qualifying library lands in one of these buckets (see [Metaprogramming Overview](../scala3-metaprogramming/metaprogramming-overview.md)):

1. **`quotes.reflect` class synthesis** — the macro builds an anonymous class implementing the trait with `Symbol.newClass` + `Symbol.newMethod(..., Flags.Override)` + `ClassDef` + `New`, generating each method body. This is by far the dominant technique (Sloth, Oxygen, Spice, Kreuzberg, smithy4s-deriving, cats-tagless, tagless-redux, ZIO `IsReloadable`, distage). The shared recipe is documented in [Derivation Mechanism Pattern](derivation-mechanism-pattern.md).
2. **Macro-generated bindings + JDK dynamic `Proxy`** — the macro produces per-method binding metadata; the callable instance is a `java.lang.reflect.Proxy` dispatched by method name at runtime (Automorph). Works because Scala traits compile to JVM interfaces.
3. **Custom operation-mirror + `derives`** — a `quotes.reflect` macro synthesizes a `Mirror`-shaped typeclass describing the trait's *operations* (the analogue of `scala.deriving.Mirror` for ADTs), which a consuming typeclass's `derived` then interprets (ops-mirror; smithy4s-deriving builds on this idea).
4. **Descriptor-only** — the macro derives a reified metadata structure, not yet a callable impl (zio-blocks RPC `RPC[T]`).

**On Mirror/typeclass derivation:** stock `scala.deriving.Mirror` reflects only ADTs (case classes/enums), so it *cannot by itself* implement a method-bearing trait. There is no real-world library doing trait-impl derivation with pure stock Mirror or pure inline derivation — a confident negative. The "Mirror-flavored" path that does exist is bucket 3 (the custom operation-mirror consumed via `derives`).

## Qualified libraries by tier

| Library | Entry point | Mechanism | Notes |
|---|---|---|---|
| **[Sloth](sloth.md)** | `client.wire[T]` | reflect class synthesis | client; @experimental; also has Scala 2 path |
| **[Automorph](automorph.md)** | `client.bind[Api]` | bindings + JDK Proxy | client; runtime name dispatch |
| **[Oxygen](oxygen-http.md)** | `DeriveClient.derived[A]` | reflect class synthesis | client → `URLayer[Client, Api]` (ZIO); @experimental |
| **[Spice](spice.md)** | `ApiClient.derive[T](baseUrl)` | reflect class synthesis | client → `rapid.Task[R]` |
| **[Kreuzberg](kreuzberg.md)** | `makeStub[T]` / `makeDispatcher` | reflect class synthesis | client + server; Scala.js; @experimental |
| **[smithy4s-deriving](smithy4s-deriving.md)** | `derives API` | operation-mirror + reflect | yields real smithy4s `Service`; @experimental, 3.4.1+ |
| **[ops-mirror](ops-mirror.md)** | `OpsMirror.Of[T]` | operation-mirror (reflect) | structural view only; consumer does synthesis |
| **[zio-blocks RPC](zio-blocks-rpc.md)** | `derives RPC` | reflect macro | descriptor only, not a client; in-flight PR |
| **[cats-tagless](cats-tagless.md)** | `derives FunctorK` … | reflect class synthesis | tagless algebra transform; @experimental |
| **[tagless-redux](tagless-redux.md)** | `WireProtocol.derive` | reflect class synthesis | tagless wire codec; reflect rewrite of cats-tagless |
| **[ZIO IsReloadable](zio-isreloadable.md)** | `IsReloadable[A].reloadable(ref)` | reflect class synthesis | hot-reload proxy; @experimental |
| **[distage TraitConstructor](distage-traitconstructor.md)** | `TraitConstructor.materialize[T]` | reflect (via reflection shim) | DI auto-impl → `Functoid[R]` |

## Excluded (and why)

- **Scala 2 macros**: scala-json-rpc, autowire, mu-scala (`@service`), Lagom.
- **External codegen**: airframe-rpc (sbt-airframe writes `.scala`), jsonrpclib & smithy4s core (Smithy IDL→codegen), scalapb/-grpc (protoc), Caliban client (GraphQL→codegen).
- **No macros / value+interpreter**: endpoints4s, tapir, sttp client (their macros, if any, do *schema* derivation, not trait-to-impl).

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Metaprogramming Overview](../scala3-metaprogramming/metaprogramming-overview.md)
- [TASTy Reflection](../scala3-metaprogramming/tasty-reflection.md)
- Per-library: [Sloth](sloth.md) · [Automorph](automorph.md) · [Oxygen](oxygen-http.md) · [Spice](spice.md) · [Kreuzberg](kreuzberg.md) · [smithy4s-deriving](smithy4s-deriving.md) · [ops-mirror](ops-mirror.md) · [zio-blocks RPC](zio-blocks-rpc.md) · [cats-tagless](cats-tagless.md) · [tagless-redux](tagless-redux.md) · [ZIO IsReloadable](zio-isreloadable.md) · [distage TraitConstructor](distage-traitconstructor.md)
