package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.ServiceInvocationCapability]] client facades from a trait.
  *
  * Instead of hand-writing every remote call:
  * {{{
  *   ServiceInvocationCapability.invoke(appId, InvocationMethodName("double"), req)[CounterState]
  * }}}
  *
  * declare a trait whose methods describe the calls and let dapr4s implement it:
  * {{{
  *   trait GreetingService:
  *     def greet(
  *       req: GreetRequest,
  *       httpMethod: HttpMethod = HttpMethod.Post,
  *       metadata: Map[MetadataKey, MetadataValue] = Map.empty,
  *     )(using ServiceInvocationCapability, JsonCodec[GreetRequest], JsonCodec[GreetResponse]): GreetResponse
  *
  *     def stats()(using ServiceInvocationCapability, JsonCodec[StatsResponse]): StatsResponse
  *
  *   val svc: GreetingService = ServiceInvocation.derive[GreetingService](AppId("greeting-service"))
  *   val sameSvc: GreetingService = ServiceInvocation.derive[GreetingService] // AppId derived from the trait name
  * }}}
  *
  * The derived instance captures only the [[dapr4s.AppId]] (a plain value), never a capability — capture-checking
  * forbids storing an `ExclusiveCapability`, so the `ServiceInvocationCapability` and the `JsonCodec`s arrive per call
  * via each method's `using` clause.
  *
  * '''Method contract''' (enforced at compile time):
  *   - The target [[dapr4s.AppId]] is either given explicitly (`derive[T](appId)`) or, with the no-argument
  *     `derive[T]`, taken from the trait's simple name (override with `@name` on the trait).
  *   - The Scala method name is used verbatim as the [[dapr4s.InvocationMethodName]]; override per method with
  *     [[name `@name`]].
  *   - The first value parameter, if present, is the request body (its type is free). A method with no value parameters
  *     maps to the no-body `invoke` overload.
  *   - `httpMethod` and `metadata`, if present, must be named exactly `httpMethod` (type [[dapr4s.HttpMethod]]) and
  *     `metadata` (type `Map[MetadataKey, MetadataValue]`), with `httpMethod` before `metadata`. Either may be omitted;
  *     the macro then supplies `HttpMethod.Post` / `Map.empty`.
  *   - The `using` clause must provide a [[dapr4s.ServiceInvocationCapability]] and the required `JsonCodec`s
  *     (`JsonCodec[Resp]` always; `JsonCodec[Req]` when there is a body).
  */
@scala.caps.assumeSafe
object ServiceInvocation:

  /** Derive a client facade for trait `T`, routing its calls to `appId`.
    *
    * Each method's Scala name is the [[dapr4s.InvocationMethodName]] it calls — `def greet` calls method `"greet"` on
    * the target app — overridable per method with [[name `@name`]]. This overload names the target [[dapr4s.AppId]]
    * explicitly; the no-argument overload derives it from `T`'s name instead.
    *
    * {{{
    *   trait GreetingService:
    *     def greet(req: GreetRequest)(using ServiceInvocationCapability, JsonCodec[GreetRequest], JsonCodec[GreetResponse]): GreetResponse
    *     @name("get-stats") def stats()(using ServiceInvocationCapability, JsonCodec[StatsResponse]): StatsResponse
    *   def GreetingService(appId: AppId): GreetingService = ServiceInvocation.derive[GreetingService](appId)
    *
    *   val svc = GreetingService(AppId("greeting-service"))
    *   DaprCapability.invoker {
    *     svc.greet(GreetRequest(...)) // → invoke(appId, InvocationMethodName("greet"), …)[GreetResponse]
    *     svc.stats()                  // → invoke(appId, InvocationMethodName("get-stats"))[StatsResponse]
    *   }
    * }}}
    */
  inline def derive[T](appId: AppId): T = ${ deriveImpl[T]('{ Some(appId) }) }

  /** Derive a client facade for trait `T`, routing its calls to the [[dapr4s.AppId]] taken from `T`'s simple name
    * (override with `@name` on the trait).
    *
    * Method names map to [[dapr4s.InvocationMethodName]]s exactly as in the `appId`-taking overload; only the source of
    * the target `AppId` differs — here it is the trait's own name rather than an argument.
    *
    * {{{
    *   @name("greeting-service") trait GreetingService:
    *     def greet(req: GreetRequest)(using ServiceInvocationCapability, JsonCodec[GreetRequest], JsonCodec[GreetResponse]): GreetResponse
    *
    *   // AppId("greeting-service") taken from the trait's `@name` (else its simple name "GreetingService"):
    *   lazy val GreetingService: GreetingService = ServiceInvocation.derive[GreetingService]
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T]('{ None }) }

  private def deriveImpl[T: Type](appIdOpt: Expr[Option[AppId]])(using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "ServiceInvocation"
    val derivedName = MacroSupport.derivedTypeName(TypeRepr.of[T].typeSymbol)
    val appId: Expr[AppId] = '{ ${ appIdOpt }.getOrElse(AppId(${ Expr(derivedName) })) }
    val httpMethodTpe = TypeRepr.of[HttpMethod]
    val metadataTpe = TypeRepr.of[Map[MetadataKey, MetadataValue]]
    val capTpe = TypeRepr.of[ServiceInvocationCapability]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)

      def fail(msg: String): Nothing = MacroSupport.fail(engine, origSym, msg)

      // Recognise the optional knobs by name + type + position.
      val httpRef = values.collectFirst {
        case (n, r, t, _) if n == "httpMethod" =>
          if !(t =:= httpMethodTpe) then fail("parameter `httpMethod` must have type HttpMethod.")
          (values.indexWhere(_._1 == n), r)
      }
      val metaRef = values.collectFirst {
        case (n, r, t, _) if n == "metadata" =>
          if !(t =:= metadataTpe) then fail("parameter `metadata` must have type Map[MetadataKey, MetadataValue].")
          (values.indexWhere(_._1 == n), r)
      }
      // First value param is the body, unless it is itself a knob (then there is no body).
      val bodyEntryOpt = values.headOption.filterNot(v => v._1 == "httpMethod" || v._1 == "metadata")

      // Reject any unexpected value parameters and enforce ordering.
      val knobNames = Set("httpMethod", "metadata")
      values.zipWithIndex.foreach { case ((n, _, _, _), idx) =>
        val isBody = bodyEntryOpt.exists(_._1 == n)
        if !isBody && !knobNames.contains(n) then
          fail(s"unexpected parameter `$n`; only the request body, `httpMethod`, and `metadata` are allowed.")
        if isBody && idx != 0 then fail("the request body must be the first value parameter.")
      }
      (httpRef, metaRef) match
        case (Some((hi, _)), Some((mi, _))) if hi > mi => fail("`httpMethod` must come before `metadata`.")
        case _                                         => ()

      val capRef = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a ServiceInvocationCapability."))

      def codecRefFor(arg: TypeRepr, role: String): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[$role] (JsonCodec[${arg.show}])."))

      val nm = MacroSupport.wireName(origSym)
      val capExpr = capRef.asExprOf[ServiceInvocationCapability]

      resTpe.asType match
        case '[resp] =>
          val respCodecExpr = codecRefFor(resTpe, "Resp").asExprOf[JsonCodec[resp]]
          bodyEntryOpt match
            case None =>
              '{
                ServiceInvocationDerivationRuntime.invokeNoBody[resp](
                  ${ capExpr },
                  ${ appId },
                  InvocationMethodName(${ Expr(nm) }),
                  ${ respCodecExpr },
                )
              }.asTerm
            case Some((_, bodyRef, reqTpe, _)) =>
              val httpExpr = httpRef.map(_._2.asExprOf[HttpMethod]).getOrElse('{ HttpMethod.Post })
              val metaExpr = metaRef
                .map(_._2.asExprOf[Map[MetadataKey, MetadataValue]])
                .getOrElse('{ Map.empty[MetadataKey, MetadataValue] })
              reqTpe.asType match
                case '[req] =>
                  val reqCodecExpr = codecRefFor(reqTpe, "Req").asExprOf[JsonCodec[req]]
                  '{
                    ServiceInvocationDerivationRuntime.invokeBody[req, resp](
                      ${ capExpr },
                      ${ appId },
                      InvocationMethodName(${ Expr(nm) }),
                      ${ bodyRef.asExprOf[req] },
                      ${ httpExpr },
                      ${ metaExpr },
                      ${ reqCodecExpr },
                      ${ respCodecExpr },
                    )
                  }.asTerm
    }
