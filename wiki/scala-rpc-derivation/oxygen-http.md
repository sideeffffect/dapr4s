# Oxygen (oxygen-http)

> Sources: Kalin-Rudnicki/Oxygen (main), 2026-06-07
> Raw: [Oxygen DeriveClient source](../../raw/scala-rpc-derivation/2026-06-07-oxygen-deriveclient-source.md)

## Overview

Oxygen is a ZIO-based toolkit; its `oxygen-http` module derives an HTTP client implementation of a user `Api` trait. `DeriveClient.derived[A]` builds an anonymous class implementing the trait, each method issuing a typed HTTP call via the ZIO `Client`, and exposes it as a `URLayer[Client, Api]`. Tier 1, `@experimental`, textbook [reflect class-synthesis](derivation-mechanism-pattern.md).

## Entry point

```scala
inline def derived[A]: DeriveClient[A] = ${ derivedImpl[A] }
def clientLayer[Api: {DeriveClient as der, Tag}]: URLayer[Client, Api] = ZLayer.fromFunction { der.client(_) }
```

`DeriveClient[Api]` has a single method `def client(client: Client): Api`. The derived value, given a `Client`, returns the implemented `Api`.

## Mechanism

1. `ApiRepr.derive[Api]` asserts `Api` is a trait and turns each method into a `RouteRepr`, inspecting the return type to classify it: `ZIO[Any|Scope, e, a]`, `ServerSentEvents`, or `LineStream` (anything else aborts). Each route computes a `MethodType` mirroring the original signature, derived api/endpoint names (`@apiName`/`@endpointName`), and request/response codecs from path/query/body schemas.
2. `Symbol.newClass(spliceOwner, "<Name>__Derived", [Object, api.typeRepr], decls, None)` where each decl is `Symbol.newMethod(p, route.name, route.methodType, Flags.Override, _)`.
3. **The Oxygen-specific body:** per endpoint, a `lazy val <name>__Impl: DerivedClientEndpointImpl[In, Out]` is bound (via `withImpl`); each method folds its term params into the route's `In` value and emits `impl.send(in, client)`. `send` runs `client.send(requestCodec.encode(in), extras)`, adapts the response, and decodes success/error by status code.
4. Assembled with `ClassDef.companion.apply` + `New.companion.apply` (Oxygen's `oxygen.quoted.*` wrapper over `quotes.reflect`, mapping 1:1 to the standard API). A `TODO` notes the define-then-`new`-inside-`client` shape is a workaround for `Symbol.newClass` not allowing a custom primary constructor.

## Notable details

- Returns `URLayer[Client, Api]` — integrates directly into ZIO's layer-based DI.
- Streaming return types (SSE, line streams) are first-class.

## Caveats

- `@experimental`; uses Oxygen's reflect wrapper rather than bare `scala.quoted`.

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Spice](spice.md) · [ZIO IsReloadable](zio-isreloadable.md) (other ZIO-flavored derivers)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
