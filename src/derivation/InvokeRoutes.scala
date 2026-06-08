package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Server-side derivation of [[dapr4s.InvokeRoute]]s from a handler type.
  *
  * `derive[T]` turns each handler method of `T` (an `object` of handlers, or a class with a no-arg constructor) into an
  * `InvokeRoute`: the method name maps verbatim (override with [[name `@name`]]) to the [[dapr4s.InvokeMethodName]],
  * the first value parameter is the request (or `Unit` if none), and the return type is the response. The handler's
  * `using` capabilities and `JsonCodec`s — and the route's own codecs — are resolved from the ambient scope at the
  * `derive` call site (so call it inside the relevant `DaprCapability.…` block).
  *
  * {{{
  *   object PaymentRoutes:
  *     def charge(req: ChargeRequest): PaymentResult = ...
  *     def refund(req: RefundRequest): Unit = ()
  *
  *   DaprApp(invokeRoutes = InvokeRoutes.derive[PaymentRoutes.type])
  * }}}
  */
@scala.caps.assumeSafe
object InvokeRoutes:

  /** Derive the [[dapr4s.InvokeRoute]]s exposed by handler type `T`.
    *
    * Each handler method's Scala name is the [[dapr4s.InvokeMethodName]] it answers — `def charge` serves incoming
    * calls to method `"charge"` — overridable per method with [[name `@name`]]. This is the inbound (server)
    * counterpart of [[Invoke.derive]], which produces the matching outbound calls; the two agree when they use the same
    * method names.
    *
    * {{{
    *   object PaymentRoutes:
    *     def charge(req: ChargeRequest): PaymentResult = ...
    *     @name("refund-payment") def refund(req: RefundRequest): Unit = ()
    *
    *   // serves InvokeMethodName("charge") and InvokeMethodName("refund-payment"):
    *   DaprApp(invokeRoutes = InvokeRoutes.derive[PaymentRoutes.type])
    * }}}
    */
  inline def derive[T]: List[InvokeRoute] = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[List[InvokeRoute]] =
    import quotes.reflect.*
    val engine = "InvokeRoutes"
    val inst = MacroSupport.instanceOf[T]
    val methods = MacroSupport.handlerMethods[T]
    if methods.isEmpty then
      report.errorAndAbort(s"$engine.derive: ${TypeRepr.of[T].typeSymbol.name} has no handler methods to derive.")

    val routes: List[Expr[InvokeRoute]] = methods.map { m =>
      val nm = MacroSupport.wireName(m)
      val inTpe = MacroSupport.valueParamType(m).getOrElse(TypeRepr.of[Unit])
      val outTpe = MacroSupport.resultTypeOf(m)
      inTpe.asType match
        case '[q] =>
          outTpe.asType match
            case '[r] =>
              val handler = Lambda(
                Symbol.spliceOwner,
                MethodType(List("req"))(_ => List(inTpe), _ => outTpe),
                (lam, args) =>
                  MacroSupport
                    .callSummoning(engine, inst, m, Some(args.head.asInstanceOf[Term]))
                    .changeOwner(lam),
              ).asExprOf[q => r]
              val qCodec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[q]]).asExprOf[JsonCodec[q]]
              val rCodec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[r]]).asExprOf[JsonCodec[r]]
              '{
                Forwarders.invocationRoute[q, r](
                  InvokeMethodName(${ Expr(nm) }),
                  ${ handler },
                  ${ qCodec },
                  ${ rCodec },
                )
              }
    }
    Expr.ofList(routes)
