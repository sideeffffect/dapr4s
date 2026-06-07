# ZIO IsReloadable (serviceProxy) — Scala 3 macro source

> Source: https://github.com/zio/zio (branch series/2.x); resolution of issue #7556
> Collected: 2026-06-07
> Published: Unknown

Files:
- `core/shared/src/main/scala-3/zio/IsReloadableVersionSpecific.scala`
- `core/shared/src/main/scala/zio/IsReloadable.scala`

NOTE: there is NO `ZIO.serviceProxy`/`ZIO.serviceWithProxy` method. The public API is `IsReloadable[A].reloadable(scopedRef)`.

```scala
trait IsReloadable[Service] {
  def reloadable(scopedRef: ScopedRef[Service]): Service
}
object IsReloadable extends IsReloadableVersionSpecific { ... }

private[zio] transparent trait IsReloadableVersionSpecific {
  @experimental
  inline given derived[A]: IsReloadable[A] = ${ IsReloadableMacros.derive[A] }
}

private object IsReloadableMacros {
  @experimental
  def derive[A: Type](using Quotes): Expr[IsReloadable[A]] =
    '{ new IsReloadable[A] { override def reloadable(scopedRef: ScopedRef[A]): A = ${ makeImpl('scopedRef) } } }

  @experimental
  def makeImpl[A: Type](service: Expr[ScopedRef[A]])(using Quotes): Expr[A] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[A]
    // validate: primary constructor has no term params; no abstract type members
    def forwarders(cls: Symbol) =
      (tpe.typeSymbol.methodMembers.view ++ tpe.typeSymbol.fieldMembers.view).flatMap { m =>
        nameAndReturnType(m.tree).flatMap { (name, tpt) =>
          val returnsZIO = tpt.tpe <:< TypeRepr.of[ZIO[_, _, _]]
          if (returnsZIO) {
            if (m.isDefDef) Some(Symbol.newMethod(cls, name, tpe.memberType(m), Flags.Override, privateWithin))
            else if (m.isValDef) Some(Symbol.newVal(cls, name, tpe.memberType(m), Flags.Override, privateWithin))
            else defect(...)
          } else if (m.flags.is(Flags.Deferred)) unsupported(s"non-ZIO member detected: $name")
          else None } }.toList
    val parents = if (tpe.typeSymbol.flags.is(Flags.Trait)) TypeTree.of[Object] :: TypeTree.of[A] :: Nil else TypeTree.of[A] :: Nil
    val cls = Symbol.newClass(Symbol.spliceOwner, s"_ZIOProxy_${tpe.typeSymbol.name}", parents.map(_.tpe), forwarders, None)

    val trace = '{ summon[Trace] }.asTerm
    val body = cls.declarations.flatMap { member =>
      // each forwarder: service.get(trace).flatMap[returnTypeArgs] { (_$1: A) =>
      //   _$1.<member>[typeParams](termParams...) }(trace)
      ... }
    Block(List(ClassDef(cls, parents, body = body)),
      Typed(New(TypeIdent(cls)).select(cls.primaryConstructor).appliedToNone, TypeTree.of[A])).asExprOf[A]
  }
}
```

Mechanism: synthesizes `_ZIOProxy_<Name>` implementing service trait `A`; each method does `scopedRef.get.flatMap(svc => svc.method(args))`, so swapping the `ScopedRef` value instantly changes behavior (resource-safe via `ScopedRef`, not plain `Ref`). Used for hot-reloadable services and test mocking.

Restrictions: `A` must be a trait or class with empty primary constructor; no abstract type members; every abstract member must return `ZIO[_,_,_]` (strict `<:<` test — `ZStream` is NOT matched); concrete non-ZIO members left untouched. `@experimental`. A separate Scala 2 blackbox macro variant lives in `scala-2/zio/internal/macros/IsReloadableMacros.scala`.
