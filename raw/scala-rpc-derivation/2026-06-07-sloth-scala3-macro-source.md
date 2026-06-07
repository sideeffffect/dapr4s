# Sloth — Scala 3 client macro source

> Source: https://github.com/cornerman/sloth (branch master)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `sloth/src/main/scala-3/internal/PlatformSpecificClient.scala`
- `sloth/src/main/scala-3/internal/Macros.scala`
- `sloth/src/main/scala/Client.scala`
- `sloth/src/main/scala/internal/Impls.scala`

## Entry point — `wire[T]`

```scala
trait PlatformSpecificClientCo[PickleType, Result[_]] { self: ClientCo[PickleType, Result] =>
  inline def wire[T]: T = ${ TraitMacro.impl[T, PickleType, Result]('self) }
}
trait PlatformSpecificClientContra[PickleType, Result[_]] { self: ClientContra[PickleType, Result] =>
  inline def wire[T]: T = ${ TraitMacro.implContra[T, PickleType, Result]('self) }
}
```

## TraitMacro.implBase (class synthesis)

```scala
@experimental
object TraitMacro {
  def impl[Trait: Type, PickleType: Type, Result[_]: Type](prefix: Expr[ClientCo[PickleType, Result]])(using Quotes): Expr[Trait] = {
    val implInstance = '{ new ClientImpl[PickleType, Result](${prefix}) }
    implBase[Trait, PickleType, Result](implInstance, prefix)
  }

  private def implBase[Trait: Type, PickleType: Type, Result[_]: Type](implInstance: Expr[Any], prefix: Expr[Client[PickleType, Result]])(using Quotes): Expr[Trait] = {
    import quotes.reflect.*
    val methods = definedMethodsInType[Trait]
    checkMethodErrors[Trait, Result](methods)
    val traitPathPart = getCustomName(TypeRepr.of[Trait].typeSymbol)

    def decls(cls: Symbol): List[Symbol] = methods.map { method =>
      val methodType = TypeRepr.of[Trait].memberType(method)
      Symbol.newMethod(cls, method.name, methodType, flags = Flags.EmptyFlags, privateWithin = method.privateWithin.fold(Symbol.noSymbol)(_.typeSymbol))
    }

    val parents = List(TypeTree.of[Object], TypeTree.of[Trait])
    val cls = Symbol.newClass(Symbol.spliceOwner, "Anon", parents.map(_.tpe), decls, selfType = None)

    val result = ValDef.let(Symbol.spliceOwner, implInstance.asTerm) { implRef =>
      val body = (cls.declaredMethods.zip(methods)).map { case (method, origMethod) =>
        val path = Method(traitPathPart, getCustomName(origMethod))
        val pathExpr = Expr(path)
        DefDef(method, { argss =>
          // pack args: Nil -> '{()}, single -> arg, many -> Expr.ofTupleFromSeq(...)
          // compute tupleType + returnType, then emit:
          // implRef.execute[TupleType, ReturnType](path, tupleExpr)
          Option.when(/*arity matches*/ true) {
            Apply(
              TypeApply(Select(implRef, /*ClientImpl.execute*/ ???), List(/*tupleTypeTree*/, /*returnTypeTree*/)),
              List(pathExpr.asTerm, /*tupleExpr*/ ???))
          }
        })
      }
      val clsDef = ClassDef(cls, parents, body = body)
      val newCls = Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[Trait])
      Block(List(clsDef), newCls)
    }
    result.asExprOf[Trait]
  }
}

private def definedMethodsInType[T: Type](using Quotes): List[quotes.reflect.Symbol] = {
  import quotes.reflect.*
  for {
    member <- TypeRepr.of[T].typeSymbol.methodMembers
    if member.flags.is(Flags.Deferred)
    if !member.flags.is(Flags.Private) && !member.flags.is(Flags.Protected) && !member.flags.is(Flags.PrivateLocal)
    if !member.isClassConstructor && !member.flags.is(Flags.Synthetic)
  } yield member
}
```

## Runtime dispatch — ClientImpl.execute

```scala
class ClientImpl[PickleType, Result[_]](client: ClientCo[PickleType, Result]) {
  def execute[T, R](path: Method, arguments: T)(implicit deserializer: Deserializer[R, PickleType], serializer: Serializer[T, PickleType]): Result[R] = {
    val serializedArguments = serializer.serialize(arguments)
    val request: Request[PickleType] = Request(path, serializedArguments)
    val result: Result[R] = Try(client.transport(request)) match {
      case Success(response) => client.failureHandler.eitherMap(response) { r =>
        deserializer.deserialize(r) match {
          case Right(value) => Right(value)
          case Left(t) => Left(ClientFailure.DeserializerError(t))
        }}
      case Failure(t) => client.failureHandler.raiseFailure(ClientFailure.TransportError(t))
    }
    client.logger.logRequest[T, R](path, arguments, result)
  }
}
```

Notes: `@experimental`. Two flavors — `Co` (`ClientImpl`, needs `Serializer[T]` + `Deserializer[R]`) and `Contra` (`ClientContraImpl`). Constraints enforced at compile time: no overloaded methods (use `@Name`), no per-method generic type params, return type constructor must conform to `Result[_]`. A separate Scala 2 macro variant lives in `src/main/scala-2`.
