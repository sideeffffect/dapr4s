# Trait-to-Implementation Derivation in Scala — Landscape Survey

> Source: Multi-source web + source-code survey (GitHub repos, docs, Scala Index), conducted 2026-06-07
> Collected: 2026-06-07
> Published: Unknown

## The pattern

A user describes a service as a **trait of method signatures**; a library automatically **synthesizes an implementation** of that trait — typically an RPC/HTTP client (each method serializes its args, performs a remote call, deserializes the response), but also servers/routers, transformed algebras, hot-reload proxies, or DI-wired instances.

## The filter applied

Include only libraries that (a) genuinely derive an *implementation from a trait* (not codegen of stubs from an IDL/protobuf, not requiring a hand-written impl), AND (b) do so via Scala 3 mechanisms — Scala 3 macros (`inline` + `scala.quoted` + `quotes.reflect`) OR Scala 3 Mirror/typeclass derivation. Exclude: Scala 2 macros, runtime reflection alone, external/explicit code generation.

## QUALIFIED (Scala 3)

Tier 1 — network RPC/HTTP clients:
- **Sloth** (cornerman) — `client.wire[T]`; quotes.reflect class synthesis.
- **Automorph** (automorph-org) — `client.bind[Api]`; macro generates `Seq[ClientBinding]`, runtime is a JDK dynamic `Proxy` (name-keyed dispatch).
- **Oxygen / oxygen-http** (Kalin-Rudnicki) — `DeriveClient.derived[A]`; quotes.reflect, returns `URLayer[Client, Api]` (ZIO). @experimental.
- **Spice** (outr) — `ApiClient.derive[T](baseUrl)`; quotes.reflect, methods return `rapid.Task[R]`, fabric.rw codecs.
- **Kreuzberg RPC** (reactivecore) — `Stub.makeStub[T]` (client) + `Dispatcher.makeDispatcher` (server); quotes.reflect, cross-platform (Scala.js). @experimental.

Tier 2 — building block / descriptor:
- **smithy4s-deriving** (neandertech) — `trait X derives API`; OpsMirror-style custom mirror + quotes.reflect, yields a real smithy4s `Service`. Scala 3.4.1+, @experimental, -Yretain-trees.
- **ops-mirror** (bishabosha) — `OpsMirror.Of[T]` (the "Mirror for operations"); transparent inline given + quotes.reflect. Provides the structural view only; the consuming typeclass's `derived` does impl synthesis.
- **zio-blocks RPC** (zio/zio-blocks PR #1270) — `derives RPC`; quotes.reflect macro, but synthesizes a metadata DESCRIPTOR `RPC[T]` only, NOT a callable client. In-flight PR.

Tier 3 — same mechanism, non-network trait-impl synthesis:
- **cats-tagless** (typelevel) — `derives FunctorK`/ApplyK/etc.; quotes.reflect `newClassOf` builds an `Alg[G]` from `Alg[F]` + `F ~> G`. @experimental.
- **tagless-redux** (goodcover) — `WireProtocol` deriver for tagless algebras; quotes.reflect class synthesis. Reflection-based cats-tagless rewrite.
- **ZIO IsReloadable** (zio/zio) — `IsReloadable[A].reloadable(scopedRef)`; quotes.reflect proxy forwarding each method to a `ScopedRef[A]`. Methods must return ZIO. @experimental.
- **distage TraitConstructor** (7mind/izumi) — DI auto-implementation of an abstract trait/class; quotes.reflect via a Java-reflection shim (`Symbol.newClass`/`ClassDef` were private). Yields a `Functoid[R]`.

## EXCLUDED (with reason)

- scala-json-rpc (shogowada): Scala 2 blackbox macros (2.12 only).
- autowire (lihaoyi): Scala 2 blackbox def macros (2.12/2.13).
- mu-scala (higherkindness): `@service` Scala 2.13 macro annotation; Scala 3 derivation only a discussion (issue #894).
- Lagom: Scala 2 def macro.
- airframe-rpc (wvlet): external codegen — sbt-airframe writes client `.scala` into src_managed (its Scala 3 quotes macros are only for airframe-surface type metadata).
- jsonrpclib (neandertech): Smithy IDL → smithy4s codegen.
- smithy4s core (disneystreaming): Smithy IDL → Scala codegen + value-level schemas (deliberately macro-free).
- scalapb / scalapb-grpc: protoc plugin codegen.
- Caliban client: GraphQL schema → calibanGenClient sbt codegen.
- endpoints4s: no macros — endpoints are plain Scala values + interpreters (Algebras/Interpreters).
- tapir: endpoint *values* + sttp client interpreter; macros only do schema derivation.
- sttp client: request builder + backend interpreter.

## Mechanism note: Mirror/typeclass derivation

Stock `scala.deriving.Mirror` only reflects ADTs (case classes/enums), so it CANNOT, by itself, derive an implementation of a method-bearing trait. There is no real-world library doing trait-impl derivation with pure stock Mirror or pure inline derivation (confident negative across docs, Scala Index, and the bishabosha "operation mirrors" article). The "Mirror-flavored" path that does exist is the **custom operation-mirror** (ops-mirror, smithy4s-deriving): a Mirror-shaped typeclass synthesized by a quotes.reflect macro, then consumed idiomatically via a `derives` clause / inline `derived`. Adjacent infra rejected: `made` (Mirror+annotations for ADTs), chimney/iron/neotype/zio-schema/Caliban (derive codecs/schemas from case classes — not trait impls).
