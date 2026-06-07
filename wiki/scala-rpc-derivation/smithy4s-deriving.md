# smithy4s-deriving

> Sources: neandertech/smithy4s-deriving (main), 2026-06-07
> Raw: [smithy4s-deriving source](../../raw/scala-rpc-derivation/2026-06-07-smithy4s-deriving-source.md)

## Overview

smithy4s-deriving inverts the usual smithy4s flow: instead of generating Scala from a Smithy IDL, you write a plain Scala trait and add `derives API`, and the library derives the full smithy4s `Service` abstraction from it — a "code-first alternative to code-generation." It is the **production-grade instance of the operation-mirror pattern** (mechanism bucket 3): a custom mirror + `quotes.reflect`. Tier 2, Scala 3.4.1+, `@experimental`, requires `-Yretain-trees`.

## Entry point

```scala
@experimental object API:
  transparent inline def derived[T](using m: InterfaceMirror.Of[T], em: EffectMirror.Of[T]) =
    ${ derivedAPIImpl[T, em.Effect]('m) }
```

`derives API` desugars to `given API[T] = API.derived[T]`. It summons an `InterfaceMirror.Of[T]` (reflects the trait's namespace/label and its tuple of methods + labels — an operation-mirror in the [ops-mirror](ops-mirror.md) sense) and an `EffectMirror.Of[T]` (extracts the single effect `F`, e.g. `IO`). `transparent inline` preserves the precise `API.Aux` refinement.

## Mechanism

`derivedAPIImpl` emits an anonymous `new DynamicAPI[T]` with `type Effect[I,E,O,SI,SO] = F[O]`:
- `operationSchemasExpression` turns each method into a smithy4s `OperationSchema`: an input **struct schema** (`Schema.struct(...)` named `<Method>Input`), an output schema, and an error **union schema** if the method declares error types — summoning a `Schema[_]` per parameter/output/error type.
- `toPolyFunction(impl)` uses `interfaceToFunctions[T,F]` to build, by reflecting over `declaredMethods`, an indexed `Vector` of `IArray[Any] => F[Any]` closures (dispatch by `op.ordinal`, args splatted via `asInstanceOf`).
- `fromPolyFunction(interp)` uses `interfaceFromFunction[T,F]` to **synthesize a `proxy` class** implementing `T` (via the standard `Symbol.newClass`/`Symbol.newMethod(Override)`/`ClassDef`/`New` recipe) where each method packs args into a `Tuple` and calls `interp(Operation(index, tuple))`.

From any derived `API` you get a genuine `smithy4s.Service` via `API.service[Alg]`, which the rest of the smithy4s ecosystem (HTTP servers/clients, JSON, etc.) consumes — so you get real client *and* server capability from the trait.

## Caveats

- `@experimental` propagates: user `derives API` code must be `@experimental`.
- `-Yretain-trees` is required to recover method **default-parameter** values (they're dropped after typer otherwise).
- Internals (`internals` package) are `private[deriving]` and may change.

## See Also

- [ops-mirror](ops-mirror.md) (the underlying operation-mirror idea)
- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
