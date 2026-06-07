# ops-mirror

> Sources: bishabosha/ops-mirror (main); "The case for operation mirrors", 2026-06-07
> Raw: [ops-mirror source](../../raw/scala-rpc-derivation/2026-06-07-ops-mirror-source.md)

## Overview

ops-mirror (by Jamie Thompson) is the reference design for "a `Mirror`, but for the *operations* of a trait rather than the fields of an ADT." `scala.deriving.Mirror` only reflects case classes/enums; ops-mirror provides `OpsMirror.Of[T]`, a compile-time structural view of a trait's methods (names, parameter types/labels, annotations, error and output types) exposed as type members. Crucially it does **no implementation synthesis** itself — it is the enabling building block that a *consuming* typeclass's `derived` uses to synthesize an actual service/client/RPC implementation. Tier 2; this is the heart of mechanism bucket 3 ("Mirror-flavored" derivation). Scala 3.3.3.

## Entry point

```scala
object OpsMirror:
  type Of[T] = OpsMirror { type MirroredType = T }
  transparent inline given reify[T]: Of[T] = ${ reifyImpl[T] }
```

`transparent inline given` so the precise refined type (the operations tuple) survives to the use site — see [Inline](../scala3-metaprogramming/inline.md).

## Mechanism (two phases)

**Phase A — reify the trait (this library).** `reifyImpl[T]` reads `cls.declaredMethods`; method names become singleton-string types (`MirroredOperationLabels`); for each method `tpe.memberType(method)` is matched (`ByNameType`/`MethodType`, rejecting curried/generic) to extract `InputTypes`, `InputLabels`, `InputMetadatas`, `OutputType`. Class- and method-level `MetaAnnotation`s are partitioned: `ErrorAnnotation[E]` recovered via the quote-pattern `'{ $a: ErrorAnnotation[t] } => Type.of[t]` (→ `ErrorType`), others encoded as `AnnotatedType(Meta, annot)` (a carrier type smuggling the annotation `Term` into the type level). Helpers `typesToTuple`/`typesFromTuple` fold the per-operation `Operation { type ... }` refinements into a tuple type. The emitted value is a bare `OpsMirror` — pure structure, no behavior.

**Phase B — a consumer synthesizes the impl (downstream).** A typeclass defines `inline def derived[T](using m: OpsMirror.Of[T]) = ${ ... }`. Its macro pattern-matches the mirror to recover the operation tuples, decodes the domain annotations (`metadata[op]` reverses the `Meta` encoding; e.g. `'{ $g: model.method }`, `'{ $p: model.source }`), and emits the real instance — the example `HttpService` builds `new HttpService[T] { val routes = Map(...) }`.

```scala
trait GreetService derives HttpService:
  @get("/greet/{name}") def greet(@path name: String): String
```

## Why it matters

It cleanly separates *reflection of the contract* (reusable, domain-agnostic) from *interpretation into an implementation* (domain-specific). One mirror, many consumers (server, client, docs). [smithy4s-deriving](smithy4s-deriving.md) is the production application of exactly this idea.

## See Also

- [smithy4s-deriving](smithy4s-deriving.md)
- [Compile-time Operations](../scala3-metaprogramming/compile-time-operations.md) (Mirror/derivation context)
- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
