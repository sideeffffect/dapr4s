# Spice (spice-api)

> Sources: outr/spice (master, commit 443bb9d), 2026-06-07
> Raw: [Spice ApiClientMacro source](../../raw/scala-rpc-derivation/2026-06-07-spice-apiclientmacro-source.md)

## Overview

Spice is an HTTP/web toolkit (by Matt Hicks). Its `api` module derives an HTTP client from a user trait via `ApiClient.derive[T](baseUrl)`: it synthesizes an `ApiClientProxy` class implementing the trait, each method issuing a REST/JSON call returning `rapid.Task[R]`, with `fabric.rw.RW` codecs resolved at compile time. Tier 1, [reflect class-synthesis](derivation-mechanism-pattern.md).

## Entry point

```scala
object ApiClient:
  inline def derive[T](baseUrl: URL): T = ${ ApiClientMacro.derive[T]('baseUrl) }
```

## Mechanism

1. Checks `T` is a trait (`Flags.Trait`); collects abstract methods (`isDefDef && Flags.Deferred`, `distinctBy(_.name)`).
2. Per method: `unwrapMethodType` peels nested `MethodType`s into parameter clauses + return type; requires the return to be `rapid.Task[R]`; `validateRW` runs `Implicits.search` for a `fabric.rw.RW` for the response and every parameter type — missing instances abort compilation.
3. `buildProxy` creates `Symbol.newClass(spliceOwner, "ApiClientProxy", [Object, T], ...)`, declaring each method by folding the parameter clauses into nested `MethodType`s ending in `rapid.Task[R]`, flagged `Override`.
4. **The Spice-specific body:** dispatch by call shape — no params → `mkGetCall` (GET); exactly one case-class param → `mkRestfulCall` (RESTful); otherwise → `mkJsonCall` (POST JSON object). Each summons the needed `RW` via `Expr.summon` and emits a quoted call to the non-macro runtime, e.g. `'{ ApiClientRuntime.doGet[r]($baseUrl, $name)(using $rw) }`.
5. `ClassDef` + `New` + `Typed(..., T)`.

`ApiClientRuntime` (shared, non-macro) builds the URL by appending `/methodName` to the base path and uses Spice's `HttpClient` (`.get.call[R]`, `.restful[Req,Res](req)`, `.post.json(...).call[R]`).

## Caveats

- Return type must be `rapid.Task[R]`.
- The endpoint URL is derived purely from the method name (`baseUrl/methodName`); call style is inferred from parameter shape, not annotations.

## See Also

- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Oxygen](oxygen-http.md) · [Sloth](sloth.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
