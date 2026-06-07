# zio-blocks RPC (PR #1270) — Scala 3 macro source

> Source: https://github.com/zio/zio-blocks/pull/1270 (OPEN, head commit 4af677c904a71beff2149ee4f1b4ac136ab0f466, branch feat/rpc)
> Collected: 2026-06-07
> Published: Unknown

PR title: "feat(rpc): add zio-blocks-rpc descriptor foundation (#1143)". State: OPEN, not merged, MERGEABLE.

Files:
- `rpc/shared/src/main/scala-3/zio/blocks/rpc/RPCCompanionVersionSpecific.scala`
- `rpc/shared/src/main/scala-3/zio/blocks/rpc/RPCMacros.scala`
- `rpc/shared/src/main/scala/zio/blocks/rpc/RPC.scala` (note: under scala/, not scala-3/)

## Entry + macro

```scala
trait RPCCompanionVersionSpecific {
  inline def derived[T]: RPC[T] = ${ RPCMacros.derived[T] }
}

def derived[T: Type](using Quotes): Expr[RPC[T]] = {
  import quotes.reflect.*
  val tpe = TypeRepr.of[T]; val tpeSym = tpe.typeSymbol
  if (!tpeSym.isClassDef || !tpeSym.flags.is(Flags.Trait))
    report.errorAndAbort(s"RPC.derived requires a trait, got: ${tpe.show}")
  val abstractMethods = tpeSym.methodMembers.filter(_.flags.is(Flags.Deferred))
    .filterNot { m => val o = m.owner; o == defn.AnyClass || o == defn.ObjectClass }
  // reject overloaded / generic / curried methods
  // trait-level @MetaAnnotation subclasses -> Chunk[MetaAnnotation] -> RPC.ServiceMetadata
  val operationExprs = abstractMethods.map { method =>
    val methodType = tpe.memberType(method)
    // MethodType(_, pts, rt); decomposeReturnType(rt) -> (successType, errorType)
    //   Either[E,A] fast-path; else implicit-search ReturnTypeDecomposer[F] reading Success/Error type members
    // summon Schema[_] for input (0->unit,1->that,N->TupleN), output, error
    buildOperation[a](...) }
  '{ RPC[T]($labelExpr, $typeIdExpr, $operationsExpr, $metadataExpr) }  // labelExpr = Expr(tpeSym.name); typeIdExpr = '{ TypeId.of[T] }
}
```

## What RPC[T] IS — a descriptor, NOT a callable client

```scala
final case class RPC[T](label: String, typeId: TypeId[T],
  operations: Chunk[RPC.Operation[?, ?]], metadata: RPC.ServiceMetadata) {
  def derive[P[_]](deriver: RpcDeriver[P]): P[T] = deriver.deriveService(this)
}
object RPC extends RPCCompanionVersionSpecific {
  final case class Operation[Input, Output](name: String, inputSchema: Schema[Input], outputSchema: Schema[Output],
    errorSchema: Option[Schema[?]], parameterNames: Chunk[String], annotations: Chunk[MetaAnnotation],
    parameterAnnotations: Chunk[Chunk[MetaAnnotation]])
  final case class ServiceMetadata(annotations: Chunk[MetaAnnotation])
}
```

CRITICAL: `RPC[T]` is a pure metadata/structure descriptor (label, TypeId, operation descriptors with input/output/error Schemas, annotations). It contains NO method bodies, NO proxy, NO dispatch/invocation. Instantiating `RPC[MyService]` does NOT give a callable `MyService`.

Derivation seam: `RpcDeriver[P]` + `RPC[T].derive(deriver): P[T]`. The only concrete protocol is JSON-RPC (`JsonRpcDeriver` → `JsonRpcProtocol[T]`), explicitly a "transport-neutral JSON-RPC 2.0 contract"; `JsonRpcProtocol.bind(name)(handler)` builds an executable codec from handlers you supply (still no network code). No generated client of the trait, no wired runtime. (`golem/runtime/rpc/*` with invoke/call/stub is a separate pre-existing Wasm-agent module, unrelated.)

`@Idempotent` is NOT built-in — it's a user-defined `MetaAnnotation` subclass in the test fixtures. Macro derivation is Scala 3 only (scala-2 stub empty). In-flight PR — may evolve before merge.
