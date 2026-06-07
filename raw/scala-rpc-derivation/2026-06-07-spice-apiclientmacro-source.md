# Spice (spice-api) — ApiClientMacro Scala 3 source

> Source: https://github.com/outr/spice (branch master, commit 443bb9d)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `api/shared/src/main/scala-3/spice/api/ApiClient.scala`
- `api/shared/src/main/scala-3/spice/api/ApiClientMacro.scala`
- `api/shared/src/main/scala/spice/api/ApiClientRuntime.scala`

```scala
object ApiClient {
  inline def derive[T](baseUrl: URL): T = ${ ApiClientMacro.derive[T]('baseUrl) }
}

object ApiClientMacro {
  def derive[T: Type](baseUrl: Expr[URL])(using Quotes): Expr[T] = {
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]; val tpeSym = tpe.typeSymbol
    if (!tpeSym.flags.is(Flags.Trait)) report.errorAndAbort(s"ApiClient.derive requires a trait...")
    val abstractMethods = tpe.baseClasses.flatMap(_.declarations.filter { s =>
      s.isDefDef && s.flags.is(Flags.Deferred) }).distinctBy(_.name)
    // each method: unwrap MethodType -> (paramClauses, returnType); require return is rapid.Task[R];
    // validateRW(...) via Implicits.search for fabric.rw.RW codecs
    buildProxy[T](baseUrl, methodInfos)
  }

  private def buildProxy[T: Type](using Quotes)(baseUrl: Expr[URL], methods: List[...]): Expr[T] = {
    import quotes.reflect.*
    val parents = List(TypeTree.of[Object], TypeTree.of[T])
    val cls = Symbol.newClass(Symbol.spliceOwner, "ApiClientProxy",
      List(TypeRepr.of[Object], TypeRepr.of[T]),
      decls = { cls => methods.map { case (method, paramClauses, returnType) =>
        val taskRetType = TypeRepr.of[rapid.Task].appliedTo(List(returnType))
        val methodTpe = paramClauses.foldRight(taskRetType: TypeRepr) { (params, acc) =>
          MethodType(params.map(_._1))(_ => params.map(_._2), _ => acc) }
        Symbol.newMethod(cls, method.name, methodTpe, Flags.Override, Symbol.noSymbol) } },
      selfType = None)

    val methodDefs = methods.map { case (origMethod, paramClauses, responseType) =>
      val newMethodSym = cls.declarations.find(s => s.name == origMethod.name && s.isDefDef).get
      DefDef(newMethodSym, { argss =>
        val allParams = paramClauses.flatten; val flatArgs = argss.flatten
        if (allParams.isEmpty) Some(mkGetCall(baseUrl, methodName, responseType))
        else if (allParams.size == 1 && isCaseClass(allParams.head._2)) Some(mkRestfulCall(...))
        else Some(mkJsonCall(...)) }) }

    val clsDef = ClassDef(cls, parents, body = methodDefs)
    Block(List(clsDef), Typed(Apply(Select(New(TypeIdent(cls)), cls.primaryConstructor), Nil), TypeTree.of[T])).asExprOf[T]
  }
  // mkGetCall/mkRestfulCall/mkJsonCall summon fabric.rw.RW[_] via Expr.summon and emit
  // '{ ApiClientRuntime.doGet[r]($baseUrl, $name)(using $rw) } etc.
}

object ApiClientRuntime {
  def doGet[R: RW](baseUrl: URL, methodName: String): Task[R] =
    HttpClient.url(baseUrl.withPath(...)).get.call[R]
  def doRestful[Req: RW, Res: RW](baseUrl: URL, methodName: String, request: Req): Task[Res] =
    HttpClient.url(baseUrl.withPath(...)).restful[Req, Res](request)
  def doJson[R: RW](baseUrl: URL, methodName: String, params: List[(String, Json)]): Task[R] =
    HttpClient.url(baseUrl.withPath(...)).post.json(obj(params*)).call[R]
}
```

Notes: requires return type `rapid.Task[R]`; call shape chosen by params (0 → GET, 1 case class → RESTful, else → JSON POST). `fabric.rw.RW` codecs are summoned and verified at compile time. The proxy class `"ApiClientProxy"` is generated inline (no standalone file). Runtime helpers in `ApiClientRuntime` (shared, non-macro).
