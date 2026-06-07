# tagless-redux — Scala 3 DeriveMacros source

> Source: https://github.com/goodcover/tagless-redux (branch master)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `encoder-macros/src/main/scala-3/com/goodcover/tagless/DeriveMacros.scala`
- `encoder-macros/src/main/scala-3/com/goodcover/tagless/WireProtocolKryoLike.scala`
- `encoder-macros/src/main/scala/com/goodcover/tagless/util/WireProtocol.scala`
- entry points: `encoder-kryo/.../MacroKryoWireProtocol.scala`, `encoder-pekko/...`, `encoder-boopickle/...`

## newClassOf — class synthesis core

```scala
extension (delegate: Option[Term])
  def newClassOf[T: Type](transformDef: DefDef => List[List[Tree]] => Option[Term], transformVal: ValDef => Option[Term],
                          additionalBody: Symbol => List[DefDef] = _ => Nil): Expr[T] =
    val T = TypeRepr.of[T].dealias.typeSymbol
    if T.flags.is(Flags.Enum) then report.errorAndAbort(s"Not supported: $T is an enum")
    if !T.isClassDef || !T.flags.is(Flags.Trait) && !T.flags.is(Flags.Abstract) then
      report.errorAndAbort(s"Not supported: $T is not a trait or abstract class")
    val name = Symbol.freshName("$anon")
    val parents = List(TypeTree.of[Object], TypeTree.of[T])
    val cls = Symbol.newClass(Symbol.spliceOwner, name, parents.map(_.tpe), sym => sym.overridableMembers(delegate), None)
    val members = cls.declarations.filterNot(_.isClassConstructor).map: member =>
      member.tree match
        case method: DefDef => DefDef(member, transformDef(method))
        case value: ValDef  => ValDef(member, transformVal(value))
        case tpe: TypeDef   => tpe
        case _ => report.errorAndAbort(...)
    val newCls = New(TypeIdent(cls)).select(cls.primaryConstructor).appliedToNone
    Block(ClassDef(cls, parents, members ++ additionalBody(cls)) :: Nil, newCls).asExprOf[T]
```

`overridableMembers` (lines 140–180): enumerates type/field/method members, filters `nonOverridableFlags = [Final, Artifact, Synthetic, Mutable, Param]` and `nonOverridableOwners = (Object, Any, AnyRef, AnyVal)`, mints `Symbol.newMethod(sym, name, tpe, flags, member.privateIn)` / `newVal` / `newTypeAlias`.

## Public entry points (thin inline defs)

```scala
@experimental object MacroKryoWireProtocol:
  inline def derive[Alg[_[_]]](using ScalaKryoSerializer): WireProtocol[Alg] =
    ${ WireProtocolKryoLike.wireProtocol[Alg, KryoImpl, KryoCodec]('KryoCodec) }
// Pekko / Boopickle variants analogous

def wireProtocol[Alg[_[_]]: Type, P[_]: Type, C <: CodecFactory[P]: Type](system: Expr[C])(using q: Quotes): Expr[WireProtocol[Alg]] =
  '{ new WireProtocol[Alg]:
       override def encoder: Alg[[X] =>> WireProtocol.Encoded[X]] = ${ deriveEncoder[Alg, P, C](system) }
       override def decoder: WireProtocol.Decoder[PairE[WireProtocol.Invocation[Alg, *], WireProtocol.Encoder]] = ${ deriveDecoder[Alg, P, C](system) } }
```

What the Scala 3 main code derives: a `WireProtocol[Alg]` (an `encoder: Alg[Encoded]` that serializes `(name, args-tuple)` + carries a response decoder; and a `decoder` that reconstructs `Invocation`s and dispatches on a real `Alg[F]`). This is an RPC-style wire codec for tagless-final algebras with pluggable backends (Kryo/Pekko/Boopickle).

Differs from cats-tagless: a reflection-based rewrite. Scala 2 side uses `scala.reflect` blackbox macros; the Scala 3 side uses `scala.quoted` + `quotes.reflect` to build the anon `ClassDef` via `Symbol.newClass`/`Symbol.newMethod`. README: "basically a straight copy from cats-tagless … but done in reflect macros." (`newTypeAlias` uses a Java-reflection hack into `dotty.tools.dotc.core.*` pending Scala 3.6+.)
