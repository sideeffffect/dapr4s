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
  * }}}
  *
  * The derived instance captures only the [[dapr4s.AppId]] (a plain value), never a capability — capture-checking
  * forbids storing an `ExclusiveCapability`, so the `ServiceInvocationCapability` and the `JsonCodec`s arrive per call
  * via each method's `using` clause.
  *
  * '''Method contract''' (enforced at compile time):
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
object ServiceInvocation:

  /** Derive an implementation of trait `T` that routes its methods to `appId`. */
  inline def derive[T](appId: AppId): T = ${ deriveImpl[T]('appId) }

  private def deriveImpl[T: Type](appId: Expr[AppId])(using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "ServiceInvocation"
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
