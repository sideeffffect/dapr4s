# smithy4s-deriving — Scala 3 macro source

> Source: https://github.com/neandertech/smithy4s-deriving (branch main; Scala 3.4.1+, experimental)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `modules/core/shared/src/main/scala/smithy4s/deriving/API.scala`
- `modules/core/shared/src/main/scala/smithy4s/deriving/internals/macros.scala`

## `derives API` entry

```scala
@experimental
object API {
  type Aux[Alg, F[_, _, _, _, _]] = API[Alg] { type Effect[I, E, O, SI, SO] = F[I, E, O, SI, SO] }
  transparent inline def derived[T](using m: InterfaceMirror.Of[T], em: EffectMirror.Of[T]) = ${
    derivedAPIImpl[T, em.Effect]('m) }
}
```

`derives API` → `given API[T] = API.derived[T]`; `transparent inline` so the precise `API.Aux` survives. Summons an `InterfaceMirror.Of[T]` (reflects namespace/label/method tuples) and `EffectMirror.Of[T]` (extracts the effect `F`).

## derivedAPIImpl — synthesizes a DynamicAPI[T]

```scala
@experimental
def derivedAPIImpl[T: Type, F[_]: Type](mirror: Expr[InterfaceMirror.Of[T]])(using q: Quotes): Expr[API.Aux[T, [I,E,O,SI,SO] =>> F[O]]] = {
  import quotes.reflect.*
  // read class symbol, docstrings, hints; pattern-match the InterfaceMirror quote to lift ns/label/operations/labels
  val opSchemas = operationSchemasExpression[operations, operationLabels, F](...)
  '{
    new DynamicAPI[T] {
      type Effect[I, E, O, SI, SO] = F[O]
      def id = ShapeId(${Expr(serviceNamespace)}, ${Expr(serviceName)})
      def operationSchemas: IndexedSeq[OperationSchema[?,?,?,?,?]] = $opSchemas.toIndexedSeq
      def toPolyFunction(impl: T): PolyFunction5[Operation, Effect] = new PolyFunction5[Operation, Effect] {
        val functions = ${ interfaceToFunctions[T, F]('impl) }.toIndexedSeq
        def apply[I,E,O,SI,SO](op: Operation[I,E,O,SI,SO]): Effect[I,E,O,SI,SO] =
          functions(op.ordinal).apply(op.input.asInstanceOf[Tuple].toIArray).asInstanceOf[F[O]]
      }
      def fromPolyFunction(interp: PolyFunction5[Operation, Effect]): T = {
        val function: (Int, Tuple) => F[Any] = (ordinal, input) => interp(Operation(ordinal, input)).asInstanceOf[F[Any]]
        ${ interfaceFromFunction[T, F]('function) }
      }
    }
  }
}
```

## interfaceToFunctions — reflect each method to IArray[Any] => F[Any]

```scala
@experimental
private def interfaceToFunctions[T: Type, F[_]: Type](algExpr: Expr[T])(using Quotes): Expr[Seq[IArray[Any] => F[Any]]] = {
  import quotes.reflect._
  Expr.ofSeq {
    TypeRepr.of[T].typeSymbol.declaredMethods.filterNot(encodesDefaultParameter).map { meth =>
      val selectMethod = algExpr.asTerm.select(meth)
      meth.paramSymss.match {
        case Nil :: Nil => '{ Function.const(${ selectMethod.appliedToNone.asExprOf[F[Any]] }) }
        case _ => '{ (input: IArray[Any]) => ${ selectMethod.appliedToArgss(/* input(i).asInstanceOf[t] */).asExprOf[F[Any]] } }
      }
    }
  }
}
```

## interfaceFromFunction — synthesizes a `proxy` class implementing T

```scala
@experimental
private def interfaceFromFunction[T: Type, F[_]: Type](fExpr: Expr[(Int, Tuple) => F[Any]])(using Quotes): Expr[T] = {
  import quotes.reflect._
  val meths = TypeRepr.of[T].typeSymbol.declaredMethods.filterNot(encodesDefaultParameter)
  def decls(cls: Symbol): List[Symbol] = meths.map { method =>
    Symbol.newMethod(cls, method.name, TypeRepr.of[T].memberType(method), flags = Flags.Override,
      privateWithin = method.privateWithin.fold(Symbol.noSymbol)(_.typeSymbol)) }
  val cls = Symbol.newClass(Symbol.spliceOwner, "proxy", parents.map(_.tpe), decls, selfType = None)
  val body = cls.declaredMethods.filterNot(encodesDefaultParameter).zipWithIndex.map { case (sym, index) =>
    DefDef(sym, argss => Some(/* fExpr.apply(index, tupleOfArgs).asInstanceOf[out] */)) }
  Block(List(ClassDef(cls, parents, body)), Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[T])).asExprOf[T]
}
```

Each method → an `OperationSchema` (input struct schema, output schema, error union) via `operationSchemasExpression`, summoning `Schema[_]` per type. From any derived `API` you get a real `smithy4s.Service` via `API.service[Alg]`, consumable by the smithy4s ecosystem (servers, clients, JSON).

Experimental: `@experimental` on the API object + macros (uses `Symbol.newClass`); user `derives API` code must be `@experimental`. `-Yretain-trees` needed to recover method default-parameter values. Scala 3.4.1+. This is the OpsMirror idea (custom mirror + quotes.reflect) made production-grade; positioned as a "code-first alternative to code-generation."
