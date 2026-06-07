# Kreuzberg RPC — Scala 3 Stub + Dispatcher macro source

> Source: https://github.com/reactivecore/kreuzberg (branch main, commit db311d8)
> Collected: 2026-06-07
> Published: Unknown

Files (cross-platform JVM + Scala.js, `@experimental`):
- `rpc/shared/src/main/scala/kreuzberg/rpc/Stub.scala`
- `rpc/shared/src/main/scala/kreuzberg/rpc/TraitAnalyzer.scala`
- `rpc/shared/src/main/scala/kreuzberg/rpc/Dispatcher.scala`
- `rpc/shared/src/main/scala/kreuzberg/rpc/CallingBackend.scala`

## Client stub — Stub.makeStub[T]

```scala
object Stub {
  @experimental
  inline def makeStub[T](backend: CallingBackend[Future]): T = ${ makeStubMacro[Future, T]('backend) }
  @experimental
  inline def makeIdStub[T](backend: CallingBackend[Id]): T = ${ makeStubMacro[Id, T]('backend) }

  @experimental
  def makeStubMacro[F[_], A](backend: Expr[CallingBackend[F]])(using Type[A], Type[F], Quotes): Expr[A] = {
    val analyzer = new TraitAnalyzer()
    import analyzer.quotes.reflect.*
    val parents = List(TypeTree.of[Object], TypeTree.of[A])
    val analyze = analyzer.analyze[A]; val methods = analyze.methods
    val effect = Expr.summon[EffectSupport[F]].getOrElse { throw ... }

    def decls(cls: Symbol): List[Symbol] = methods.map { method =>
      Symbol.newMethod(parent = cls, name = method.name, tpe = generateMethodType(method)) }
    // generateMethodType: return F[R] where R = method.returnType.typeArgs.head; rebuild curried/implicit param clauses

    val cls = Symbol.newClass(Symbol.spliceOwner, analyze.name + "_impl", parents = parents.map(_.tpe), decls, selfType = None)

    def multiArgImplementation(method: analyzer.Method, args: List[List[Tree]]): Term = {
      val request = encodeNamedArgs(method.paramNames, method.paramTypes, args.flatten) // folds ParamEncoder.encode into Request
      makeDecode(method) { '{ $backend.call(${Expr(analyze.apiName)}, ${Expr(method.name)}, ${request}) } }
      // makeDecode: '{ ${effect}.decodeResponse[R]($inner)(using $returnDecoder) }
    }

    val methodDefinitions = methods.map { method =>
      DefDef(cls.declaredMethod(method.name).head, argss => Some(multiArgImplementation(method, argss))) }
    val clsDef = ClassDef(cls, parents, body = methodDefinitions)
    Block(List(clsDef), Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[A])).asExprOf[A]
  }
}
```

## TraitAnalyzer.analyze[T] (shared)

```scala
def analyze[T](using Type[T]): Analyze = {
  val tree = TypeRepr.of[T]
  val symbol = (tree match { case a: AppliedType => a.tycon; case _ => tree }).typeSymbol
  // reads @ApiName annotation override
  val methods = for {
    member <- symbol.methodMembers
    if !member.isClassConstructor && !member.flags.is(Flags.Synthetic) && member.flags.is(Flags.Deferred)
  } yield { /* decode DefDef, param clauses (TermParamClause -> isGiven/isImplicit), returnType */ }
  Analyze(symbol.name, apiNameOverride, methods)
}
```

## Server dispatcher — Dispatcher.makeDispatcher[A]

```scala
trait Dispatcher[F[_]] {
  def handles(serviceName: String): Boolean
  def call(serviceName: String, name: String, request: Request): F[Response]
  def asCallingBackend: CallingBackend[F] = ...
}
object Dispatcher {
  @experimental inline def makeDispatcher[A](handler: A): Dispatcher[Future] = ${ makeDispatcherMacro[Future, A]('handler) }

  @experimental def makeDispatcherMacro[F[_], A](handler: Expr[A])(using Quotes, Type[F], Type[A]): Expr[Dispatcher[F]] = {
    // declares: handles, call, and one call_<methodName>(input: Request): F[Response] per method
    // call body: if serviceName != apiName -> UnknownServiceError; else if/else chain on name -> call_<name>
    // call_<m>: summon ParamDecoder[X] per param, ValDef.let-bind decoded values, handler.select(method.symbol).appliedToArgss(...),
    //           then effect.encodeResponse($called)(using ResponseEncoder[R]); wrapped in try/catch
    // Symbol.newClass(... parents [Object, Dispatcher[F]] ...) + ClassDef + new
  }
}
```

Symmetry: Stub = encode-args → backend.call → decode-response (`ParamEncoder` + `ResponseDecoder`); Dispatcher = decode-args → invoke handler → encode-response (`ParamDecoder` + `ResponseEncoder`). Both peel `R` from `F[R]` via `returnType.typeArgs.head`. Circe-based codecs must be in scope.
