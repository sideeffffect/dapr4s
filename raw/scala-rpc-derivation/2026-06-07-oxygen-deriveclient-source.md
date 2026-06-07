# Oxygen (oxygen-http) — DeriveClient Scala 3 macro source

> Source: https://github.com/Kalin-Rudnicki/Oxygen (branch main)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `modules/http/zio/src/main/scala/oxygen/http/client/DeriveClient.scala`
- `modules/http/zio/src/main/scala/oxygen/http/client/generic/EndpointRepr.scala`
- `modules/http/zio/src/main/scala/oxygen/http/client/generic/DerivedClientEndpointImpl.scala`
- `modules/http/zio/src/main/scala/oxygen/http/core/generic/ApiRepr.scala`

```scala
@experimental
trait DeriveClient[Api] {
  def client(client: Client): Api
}
object DeriveClient {

  def clientLayer[Api: {DeriveClient as der, Tag}]: URLayer[Client, Api] =
    ZLayer.fromFunction { der.client(_) }

  private[client] def derivedImpl[Api: Type](using Quotes): Expr[DeriveClient[Api]] = {
    val api: ApiRepr[Api] = ApiRepr.derive[Api]
    val newClassSym: Symbol =
      Symbol.newClass(
        owner = Symbol.spliceOwner,
        name = s"${api.typeRepr.typeSymbol.name}__Derived",
        parents = List(TypeRepr.of[Object], api.typeRepr),
        decls = p => api.routes.toList.map { r =>
          Symbol.newMethod(p, r.defDef.name, r.methodType, Flags.Override, Symbol.noSymbol) },
        selfType = None,
      )
    val endpoints: ArraySeq[EndpointRepr[Api]] = api.routes.map(EndpointRepr[Api](_, newClassSym))

    def clientImpl(queue: List[EndpointRepr[Api]], rStack: List[EndpointRepr.WithImpl[Api]])(using Quotes): Expr[DeriveClient[Api]] =
      queue match {
        case head :: tail => head.withImpl[DeriveClient[Api]] { impl => clientImpl(tail, impl :: rStack) }
        case Nil =>
          '{
            new DeriveClient[Api] {
              override def client(client: Client): Api = ${
                val classDef: ClassDef =
                  ClassDef.companion.apply(
                    cls = newClassSym,
                    parents = List(TypeTree.of[Object], TypeTree.ref(api.sym)),
                    body = rStack.reverse.map(_.toDefinition('client)))
                Block.companion.apply(classDef :: Nil,
                  New.companion.apply(TypeTree.ref(newClassSym)).select(newClassSym.primaryConstructor).appliedToNone
                ).asExprOf[Api]
              }
            }
          }
      }
    clientImpl(endpoints.toList, Nil)
  }

  inline def derived[A]: DeriveClient[A] = ${ derivedImpl[A] }
}
```

Each method body (EndpointRepr.toDefinition):

```scala
val outExpr: Expr[Out] = '{ $implExpr.send($inExpr, $clientExpr) }
```

DerivedClientEndpointImpl.send:

```scala
final def send(in: In, client: Client): Out =
  makeOut {
    for {
      rawResponse <- client.send(requestCodec.encode(in), extras)
      response <- ReceivedResponse.fromResponse(rawResponse)
    } yield response
  }
```

Notes: `oxygen.quoted.*` is Oxygen's thin re-export wrapper over `scala.quoted`/`quotes.reflect` (hence `ClassDef.companion.apply` etc.); maps 1:1 to standard reflection. `ApiRepr.derive` asserts `Api` is a trait and reads route info from each method's ZIO/SSE/LineStream return type. Returns `URLayer[Client, Api]`. Whole `DeriveClient` is `@experimental`.
