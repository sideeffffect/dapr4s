# ops-mirror — Scala 3 OpsMirror macro source

> Source: https://github.com/bishabosha/ops-mirror (branch main); article https://bishabosha.github.io/articles/the-case-for-operation-mirrors.html
> Collected: 2026-06-07
> Published: Unknown

Files:
- `src/macros/OpsMirror.scala`
- `examples/serverlib/HttpService.scala`
- `examples/serverlib/ServerMacros.scala`
- `examples/GreetService.scala`

## OpsMirror trait + Of[T] + the transparent inline given

```scala
sealed trait OpsMirror:
  type Metadata <: Tuple
  type MirroredType
  type MirroredLabel
  type MirroredOperations <: Tuple
  type MirroredOperationLabels <: Tuple

sealed trait Meta
sealed trait VoidType
open class MetaAnnotation extends scala.annotation.RefiningAnnotation
open class ErrorAnnotation[E] extends MetaAnnotation

sealed trait Operation:
  type Metadata <: Tuple
  type InputTypes <: Tuple
  type InputLabels <: Tuple
  type InputMetadatas <: Tuple
  type ErrorType
  type OutputType

object OpsMirror:
  type Of[T] = OpsMirror { type MirroredType = T }
  transparent inline given reify[T]: Of[T] = ${ reifyImpl[T] }
```

## reifyImpl — reflect a trait into a typelevel operations structure

```scala
private def reifyImpl[T: Type](using Quotes): Expr[Of[T]] =
  import quotes.reflect.*
  val cls    = TypeRepr.of[T].classSymbol.get
  val decls  = cls.declaredMethods
  val labels = decls.map(m => ConstantType(StringConstant(m.name)))
  // class-level annotations partitioned: ErrorAnnotation[E] -> error type via '{ $a: ErrorAnnotation[t] } => Type.of[t]
  //   other MetaAnnotations encoded as AnnotatedType(TypeRepr.of[Meta], annot)
  val ops = decls.map(method =>
    tpe.memberType(method) match
      case ByNameType(res) => /* output only */
      case MethodType(paramNames, paramTpes, res) =>
        // InputTypes, InputLabels (singleton strings), InputMetadatas; reject curried/generic
      case _: PolyType => report.errorAndAbort(s"generic method ${method.name} is not supported")
    // build Operation { type InputTypes = i; type InputLabels = l; type ErrorType = e; type OutputType = o; ... }
  )
  '{
    (new OpsMirror:
      type Metadata = meta & Tuple
      type MirroredType = T
      type MirroredLabel = label
      type MirroredOperations = ops & Tuple
      type MirroredOperationLabels = labels & Tuple
    ): OpsMirror.Of[T] { type MirroredOperations = ops & Tuple; type MirroredOperationLabels = labels & Tuple; ... }
  }
```

Helpers: `typesFromTuple`/`typesToTuple` (fold a `List[Type[?]]` to/from a right-nested `t *: rest`), `metadata[Op]` (reverses the `Meta` annotation encoding via `AnnotatedType(_, annot) => annot.asExpr`).

## A consuming typeclass uses the mirror to synthesize the impl

```scala
trait HttpService[T]:
  val routes: Map[String, HttpService.Route]
object HttpService:
  inline def derived[T](using m: OpsMirror.Of[T]): HttpService[T] = ${ ServerMacros.derivedImpl[T]('m) }

// ServerMacros.derivedImpl: walks MirroredOperations via typesFromTuple, decodes domain annotations
//   ('{ $g: model.method }, '{ $p: model.source }), builds Route/Input values, emits:
//   new HttpService[T] { val routes = Map(${Varargs(serviceExprs)}*) }

@failsWith[Int]
trait GreetService derives HttpService:
  @get("/greet/{name}") def greet(@path name: String): String
  @post("/greet/{name}") def setGreeting(@path name: String, @body greeting: String): Unit
```

KEY DISTINCTION: ops-mirror is ONLY the structural-view provider (the analogue of `scala.deriving.Mirror`, but for a trait's *operations* rather than an ADT's fields). `reifyImpl` performs NO implementation synthesis. The consuming typeclass's `derived` (e.g. `ServerMacros.derivedImpl`) interprets the structure and emits the actual instance. Same mirror, many possible consumers (server, client, …). Scala 3.3.3.
