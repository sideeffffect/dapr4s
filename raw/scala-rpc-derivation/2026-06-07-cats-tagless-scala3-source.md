# cats-tagless — Scala 3 DeriveMacros source

> Source: https://github.com/typelevel/cats-tagless (branch master); all @experimental
> Collected: 2026-06-07
> Published: Unknown

Files:
- `core/src/main/scala-3/cats/tagless/macros/DeriveMacros.scala`
- `core/src/main/scala-3/cats/tagless/macros/MacroFunctorK.scala`
- `core/src/main/scala-3/cats/tagless/derived/DerivedFunctorK.scala`
- `core/src/main/scala/cats/tagless/FunctorK.scala`

## derives chain

```scala
// object FunctorK extends DerivedFunctorK, so `derives FunctorK` -> FunctorK.derived
trait DerivedFunctorK:
  @experimental inline def derived[Alg[_[_]]]: FunctorK[Alg] = summonFrom:
    case derived: Derived[FunctorK[Alg]] => derived.instance
    case _ => Derive.functorK[Alg]
// Derive.functorK -> MacroFunctorK.derive
```

## MacroFunctorK

```scala
@experimental
object MacroFunctorK:
  inline def derive[Alg[_[_]]]: FunctorK[Alg] = ${ functorK }
  def functorK[Alg[_[_]]: Type](using Quotes): Expr[FunctorK[Alg]] = '{
    new FunctorK[Alg]:
      def mapK[F[_], G[_]](alg: Alg[F])(fk: F ~> G): Alg[G] = ${ deriveMapK('alg, 'fk) }
  }
  private[macros] def deriveMapK[Alg[_[_]]: Type, F[_]: Type, G[_]: Type](alg: Expr[Alg[F]], fk: Expr[F ~> G])(using q: Quotes): Expr[Alg[G]] =
    import quotes.reflect.*
    given DeriveMacros[q.type] = new DeriveMacros
    alg.transformTo[Alg[G]](
      args = case (_, tpe, arg) if tpe.contains(G) => /* contramapK via summoned ContravariantK */,
      body = case (_, tpe, body) if tpe.contains(G) => /* mapK via summoned FunctorK, applied to fk */)
```

## DeriveMacros — newClassOf (the class synthesis core)

```scala
extension (delegate: Option[Term])
  def newClassOf[T: Type](transformDef: DefDef => List[List[Tree]] => Option[Term], transformVal: ValDef => Option[Term]): Expr[T] =
    val T = TypeRepr.of[T].dealias.typeSymbol
    if T.flags.is(Flags.Enum) then report.errorAndAbort(s"Not supported: $T is an enum")
    if !T.isClassDef || !T.flags.is(Flags.Trait) && !T.flags.is(Flags.Abstract) then
      report.errorAndAbort(s"Not supported: $T is not a trait or abstract class")
    val name = Symbol.freshName("$anon")
    val parents = List(TypeTree.of[Object], TypeTree.of[T])
    val cls = Symbol.newClass(Symbol.spliceOwner, name, parents.map(_.tpe), _.overridableMembers(delegate), None)
    val members = cls.declarations.filterNot(_.isClassConstructor).map: member =>
      member.tree match
        case method: DefDef => DefDef(member, transformDef(method))
        case value: ValDef => ValDef(member, transformVal(value))
        case tpe: TypeDef => tpe
        case _ => report.errorAndAbort(...)
    val newCls = New(TypeIdent(cls)).select(cls.primaryConstructor).appliedToNone
    Block(ClassDef(cls, parents, members) :: Nil, newCls).asExprOf[T]
```

`overridableMembers` enumerates `typeMembers ++ fieldMembers ++ methodMembers`, filters non-overridable (Final/Synthetic/Param, Object/Any owners, `$default$`), and mints fresh override symbols (`Symbol.newMethod`/`newVal`/`newTypeAlias`) substituting the type member `F`→`G`. `transformTo` builds each body by delegating to the original `alg` (`term.call(sym)(argss)`) then post-processing the result.

The shared `DeriveMacros` engine (`transformTo`, `combineTo`, `newClassOf`) powers all K-typeclasses (FunctorK/ApplyK/ContravariantK/InvariantK/SemigroupalK), instrumentation, and Aspect. This is genuine trait-to-impl synthesis — the same machinery as an RPC proxy, but the "transport" is delegation + a natural transformation `F ~> G`. (`newTypeAlias` uses a reflective hack pending Scala 3.6+ `Symbol.newTypeAlias`.)
