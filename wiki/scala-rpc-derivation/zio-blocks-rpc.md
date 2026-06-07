# zio-blocks RPC (PR #1270)

> Sources: zio/zio-blocks PR #1270 (head 4af677c, OPEN), 2026-06-07
> Raw: [zio-blocks RPC source](../../raw/scala-rpc-derivation/2026-06-07-zio-blocks-rpc-pr1270-source.md)

## Overview

An in-flight zio-blocks PR adds `derives RPC`, a Scala 3 macro that reifies a service trait into an `RPC[T]` value. It qualifies on **mechanism** (genuine `quotes.reflect` derivation from a trait) but with an important caveat: `RPC[T]` is a **metadata descriptor**, not a callable client. Tier 2, descriptor-only (mechanism bucket 4). PR is OPEN and may evolve.

## Entry point

```scala
trait RPCCompanionVersionSpecific:
  inline def derived[T]: RPC[T] = ${ RPCMacros.derived[T] }
```

## Mechanism

`RPCMacros.derived[T]`:
1. Enforces `T` is a trait (`isClassDef && Flags.Trait`, else `report.errorAndAbort`).
2. Collects `Flags.Deferred` methods (minus `Any`/`Object`-owned); rejects overloaded, generic, and curried methods.
3. Reads trait-level `MetaAnnotation` subclasses into `RPC.ServiceMetadata`.
4. Per method: matches `MethodType(_, pts, rt)`; `decomposeReturnType(rt)` splits into `(successType, errorType)` — fast-path for `Either[E,A]`, otherwise implicit-searches a `ReturnTypeDecomposer[F]` reading its `Success`/`Error` type members (the extensibility hook for ZIO/cats/Kyo). Summons a `Schema[_]` for input (0→unit, 1→that, N→`TupleN`), output, and error.
5. Emits `'{ RPC[T]($label, $typeId, $operations, $metadata) }`.

## Critical caveat — descriptor, not a client

```scala
final case class RPC[T](label: String, typeId: TypeId[T],
  operations: Chunk[RPC.Operation[?, ?]], metadata: RPC.ServiceMetadata):
  def derive[P[_]](deriver: RpcDeriver[P]): P[T] = deriver.deriveService(this)
```

`RPC[T]` contains only reified structure (operation names, input/output/error `Schema`s, annotations) — **no method bodies, no proxy, no dispatch.** Instantiating `RPC[MyService]` does not give you something callable, analogous to how `derives Schema` captures a data type. A derivation seam (`RpcDeriver[P]`, `RPC[T].derive(deriver): P[T]`) exists; the only concrete protocol is a transport-neutral JSON-RPC 2.0 contract (`JsonRpcProtocol[T]`, with a `bind(name)(handler)` that builds an executable codec from *handlers you supply*). There is no generated client of the trait and no wired network runtime in the PR.

`@Idempotent` is not built-in — it's a user-defined `MetaAnnotation` in the tests. Macro derivation is Scala 3 only (the scala-2 stub is empty).

## See Also

- [ops-mirror](ops-mirror.md) ("derives Schema"-style reification)
- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
