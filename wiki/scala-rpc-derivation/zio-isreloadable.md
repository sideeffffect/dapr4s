# ZIO IsReloadable (service proxy)

> Sources: zio/zio (series/2.x), issue #7556, 2026-06-07
> Raw: [ZIO IsReloadable source](../../raw/scala-rpc-derivation/2026-06-07-zio-isreloadable-source.md)

## Overview

ZIO core's `IsReloadable[A]` typeclass derives a **proxy** implementing a service trait `A`, where every method resolves the *current* service from a `ScopedRef[A]` and delegates to it. Swapping the `ScopedRef`'s value instantly changes behavior — the basis for hot-reloadable services and test mocking. It is the resolution of zio/zio issue #7556. Tier 3, `@experimental`; standard [reflect class-synthesis recipe](derivation-mechanism-pattern.md).

> Note: there is **no** `ZIO.serviceProxy`/`serviceWithProxy` method — the public API is `IsReloadable[A].reloadable(scopedRef)`.

## Entry point

```scala
trait IsReloadable[Service]:
  def reloadable(scopedRef: ScopedRef[Service]): Service

private[zio] transparent trait IsReloadableVersionSpecific:
  @experimental inline given derived[A]: IsReloadable[A] = ${ IsReloadableMacros.derive[A] }
```

## Mechanism

`derive[A]` quotes `new IsReloadable[A] { def reloadable(scopedRef) = ${ makeImpl('scopedRef) } }`. `makeImpl`:
1. Validates `A`: primary constructor must have no term params; no abstract type members.
2. `forwarders` scans `methodMembers ++ fieldMembers`; for each whose return type `<:< ZIO[_,_,_]` it mints an override symbol (`Symbol.newMethod`/`Symbol.newVal`, `Flags.Override`). An abstract member that does **not** return `ZIO` aborts compilation.
3. `Symbol.newClass(spliceOwner, "_ZIOProxy_<Name>", parents, forwarders, None)`.
4. **Step-4 body:** each forwarder emits `scopedRef.get(trace).flatMap[returnTypeArgs] { (svc: A) => svc.<member>[typeParams](termParams...) }(trace)` — i.e. fetch the live service from the `ScopedRef`, then delegate. This is what makes reloads take effect immediately and resource-safely (a `ScopedRef`, not a plain `Ref`, finalizes the old service on swap).
5. `ClassDef` + `New` + `Typed(..., A)`.

## Restrictions

- `A` must be a trait or a class with an empty primary constructor; no abstract type members.
- Every **abstract** member must return `ZIO[_,_,_]` — the `<:<` test is strict, so `ZStream`-returning methods are *not* matched. Concrete non-ZIO members are left untouched.
- `@experimental`. A separate Scala 2 blackbox-macro variant exists (out of scope).

## See Also

- [Oxygen](oxygen-http.md) (other ZIO-flavored deriver)
- [Derivation Mechanism Pattern](derivation-mechanism-pattern.md)
- [Trait-to-Implementation Derivation Overview](trait-to-impl-derivation-overview.md)
