package dapr4s.bindings

import dapr4s.derivation.*

import dapr4s.*
import dapr4s.bindings.*
import scala.quoted.*

/** Server-side derivation of [[dapr4s.BindingRoute]]s from a handler type.
  *
  * `derive[T]` turns each handler method of `T` (an `object` of handlers, or a class with a no-arg constructor) into a
  * [[dapr4s.BindingRoute]]: the method name maps verbatim (override with [[name `@name`]]) to the
  * [[dapr4s.BindingName]] of the input binding it serves, the single value parameter is the decoded payload, and the
  * method returns `Unit` (input bindings are fire-and-forget). The handler's `using` capabilities/codecs and the
  * route's own codec are resolved from the ambient scope at the `derive` call site.
  *
  * This is the inbound counterpart of the outbound [[Bindings]] facade. Unlike service invocation or pub/sub, the two
  * are '''not''' a request/response contract: the outbound side issues [[dapr4s.BindingOperation]]s (`create`, `get`,
  * …) on a binding, while an input binding merely delivers payloads to the app keyed by binding name. The two
  * directions are independent, so there is no `deriveChecked` overload here.
  *
  * {{{
  *   object IngestRoutes:
  *     def orders(payload: OrderEvent): Unit = ...                       // binding "orders"
  *     @name("audit-log") def audit(payload: AuditEntry)(using Logger): Unit = ...
  *
  *   DaprApp(bindings = BindingRoutes.derive[IngestRoutes.type])
  * }}}
  */
@scala.caps.assumeSafe
object BindingRoutes:

  /** Derive the [[dapr4s.BindingRoute]]s exposed by handler type `T`.
    *
    * Each handler method's Scala name is the [[dapr4s.BindingName]] of the input binding it serves — `def orders`
    * handles deliveries from binding `"orders"` — overridable per method with [[name `@name`]]. Each method takes the
    * decoded payload as its single value parameter and returns `Unit`.
    *
    * {{{
    *   object IngestRoutes:
    *     def orders(payload: OrderEvent): Unit = ...
    *     @name("audit-log") def audit(payload: AuditEntry): Unit = ...
    *
    *   // serves BindingName("orders") and BindingName("audit-log"):
    *   DaprApp(bindings = BindingRoutes.derive[IngestRoutes.type])
    * }}}
    */
  inline def derive[T]: List[BindingRoute] = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[List[BindingRoute]] =
    import quotes.reflect.*
    val engine = "BindingRoutes"
    val inst = MacroSupport.instanceOf[T]
    val methods = MacroSupport.handlerMethods[T]
    if methods.isEmpty then
      report.errorAndAbort(s"$engine.derive: ${TypeRepr.of[T].typeSymbol.name} has no handler methods to derive.")

    val routes = methods.map { m =>
      val nm = MacroSupport.wireName(m)
      val inTpe =
        MacroSupport
          .valueParamType(m)
          .getOrElse(MacroSupport.fail(engine, m, "a binding handler needs a payload parameter."))
      if !MacroSupport.isUnit(MacroSupport.resultTypeOf(m)) then
        MacroSupport.fail(engine, m, "a binding handler must return Unit (input bindings are fire-and-forget).")
      inTpe.asType match
        case '[t] =>
          val handler = Lambda(
            Symbol.spliceOwner,
            MethodType(List("payload"))(_ => List(inTpe), _ => TypeRepr.of[Unit]),
            (lam, args) =>
              MacroSupport.callSummoning(engine, inst, m, Some(args.head.asInstanceOf[Term])).changeOwner(lam),
          ).asExprOf[t => Unit]
          val codec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[t]]).asExprOf[JsonCodec[t]]
          '{ Forwarders.bindingRoute[t](BindingName(${ Expr(nm) }), ${ handler }, ${ codec }) }
    }
    Expr.ofList(routes)
