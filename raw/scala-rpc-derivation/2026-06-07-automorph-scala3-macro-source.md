# Automorph — Scala 3 client macro source

> Source: https://github.com/automorph-org/automorph (branch main, commit eeb3adb)
> Collected: 2026-06-07
> Published: Unknown

Files:
- `meta/src/main/scala-3/automorph/client/meta/ClientBase.scala`
- `meta/src/main/scala-3/automorph/client/meta/ClientBindingGenerator.scala`
- `meta/src/main/scala-3/automorph/reflection/ApiReflection.scala`
- `common/meta/src/main/scala/automorph/client/ClientBinding.scala`
- `core/src/main/scala/automorph/RpcClient.scala`

## bind[Api] — builds a JDK dynamic Proxy (NOT an anonymous class)

```scala
inline def bind[Api <: AnyRef]: Api = bind[Api](identity)

inline def bind[Api <: AnyRef](mapName: String => String): Api =
  val bindings = ClientBindingGenerator.generate[Node, Codec, Effect, Context, Api](
    rpcProtocol.messageCodec
  ).map(b => b.function.name -> b).toMap
  val classTag = summonInline[ClassTag[Api]]
  Proxy.newProxyInstance(
    this.getClass.getClassLoader,
    Array(classTag.runtimeClass),
    (_, method, arguments) =>
      bindings.get(method.getName).map { binding =>
        val callArguments = Option(arguments).getOrElse(Array.empty[AnyRef])
        val (argumentValues, requestContext) =
          if binding.acceptsContext && callArguments.nonEmpty then
            callArguments.dropRight(1).toSeq -> Some(callArguments.last.asInstanceOf[Context])
          else callArguments.toSeq -> None
        val argumentNodes = binding.function.parameters.zip(argumentValues).map { (parameter, argument) =>
          val encodeArgument = binding.argumentEncoders.getOrElse(parameter.name, throw ...)
          parameter.name -> Try(encodeArgument(argument)).recoverWith { case e => Failure(InvalidRequest(...)) }.get
        }
        performCall(mapName(method.getName), argumentNodes,
          (resultNode, responseContext) => binding.decodeResult(resultNode, responseContext), requestContext)
      }.getOrElse(throw UnsupportedOperationException(s"Invalid method: ${method.getName}")),
  ).asInstanceOf[Api]

def performCall[Result](function: String, arguments: Seq[(String, Node)],
  decodeResult: (Node, Context) => Result, requestContext: Option[Context]): Effect[Result]
```

## ClientBindingGenerator — compile-time binding synthesis

```scala
inline def generate[Node, Codec <: MessageCodec[Node], Effect[_], Context, Api <: AnyRef](codec: Codec): Seq[ClientBinding[Node, Context]] =
  ${ generateMacro[Node, Codec, Effect, Context, Api]('codec) }

private def generateMacro[Node: Type, Codec <: MessageCodec[Node], Effect[_]: Type, Context: Type, Api <: AnyRef: Type](codec: Expr[Codec])(using quotes: Quotes): Expr[Seq[ClientBinding[Node, Context]]] =
  val ref = ClassReflection(quotes)
  val apiMethods = ApiReflection.apiMethods[Api, Effect](ref)
  val validMethods = apiMethods.flatMap(_.swap.toOption) match
    case Seq() => apiMethods.flatMap(_.toOption)
    case errors => ref.q.reflect.report.errorAndAbort(s"Failed to bind API methods:\n...")
  val bindings = validMethods.map { method =>
    generateBinding[Node, Codec, Effect, Context, Api](ref)(method, codec) }
  Expr.ofSeq(bindings)
```

## ApiReflection.apiMethods — enumerate + validate public trait methods

```scala
def apiMethods[Api: Type, Effect[_]: Type](ref: ClassReflection): Seq[Either[String, ref.RefMethod]] =
  import ref.q.reflect.TypeRepr
  val rootMethodNames = Seq(TypeRepr.of[AnyRef], TypeRepr.of[Product]).flatMap { baseType =>
    ref.methods(baseType).filter(_.public).map(_.name) }.toSet
  val methods = ref.methods(TypeRepr.of[Api]).filter(_.public).filter(m => !rootMethodNames.contains(m.name))
  val methodNameCount = methods.groupBy(_.name).view.mapValues(_.size).toMap
  methods.map(method => validateApiMethod[Api, Effect](ref)(method, methodNameCount))
```

`ClientBinding` is a plain case class (function descriptor + argument encoders map + result decoder + acceptsContext). Validation rejects type-parameterized, non-runtime-callable, wrong-effect-return, and overloaded methods at compile time. A parallel Scala 2 macro variant exists.

KEY DESIGN NOTE: Automorph does NOT synthesize an anonymous `class new Api {...}`. The macro produces only the `Seq[ClientBinding]`; the callable `Api` is a `java.lang.reflect.Proxy` (works because Scala traits compile to JVM interfaces), dispatched by method name at runtime. Binding a *class* is therefore disallowed.
