package dapr4s.invoke

import dapr4s.derivation.*

import dapr4s.*
import dapr4s.invoke.*
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
  *
  * '''Dual.''' [[Invoke]] is the outbound counterpart. Use [[deriveChecked]] to bind a server impl to the same caller
  * `Contract` trait that `Invoke.derive[Contract]` turns into calls, keeping the two sides type-safe across the wire.
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

  /** Derive the [[dapr4s.InvokeRoute]]s of handler type `Impl`, '''checked''' against caller contract trait `Contract`.
    *
    * Same result as [[derive]], but bound to the dual [[Invoke]] facade through the shared `Contract` trait. Where
    * `Invoke.derive[Contract]` turns the trait into outbound calls, `InvokeRoutes.deriveChecked[Contract, Impl]` turns
    * the plain handler `Impl` into the routes that answer them. The wire [[dapr4s.InvokeMethodName]] of each route is
    * taken from `Contract` (so a `@name` on the trait governs both sides), and the macro verifies — matching by Scala
    * method name — that `Impl` implements every `Contract` method with the same request and response types. `Impl`
    * stays a plain handler: no `InvokeCapability`, no `httpMethod`/`metadata` knobs, and free to take its own ambient
    * `using` dependencies.
    *
    * {{{
    *   trait GreetingService:
    *     def greet(req: GreetRequest)(using InvokeCapability, JsonCodec[GreetRequest], JsonCodec[GreetResponse]): GreetResponse
    *
    *   object GreetingServiceImpl:
    *     def greet(req: GreetRequest): GreetResponse = ...
    *
    *   // checked against GreetingService; serves InvokeMethodName("greet"):
    *   DaprApp(invokeRoutes = InvokeRoutes.deriveChecked[GreetingService, GreetingServiceImpl.type])
    * }}}
    *
    * @see
    *   [[Invoke.derive]] — the dual outbound facade derived from the same `Contract`.
    */
  inline def deriveChecked[Contract, Impl]: List[InvokeRoute] = ${ deriveCheckedImpl[Contract, Impl] }

  /** The optional caller-only knobs of an [[Invoke]] contract method, skipped when reading its request body. */
  private val invokeKnobs = Set("httpMethod", "metadata")

  /** Build one [[dapr4s.InvokeRoute]] answering wire name `nm` by calling handler method `m` on `inst`, with request
    * type `inTpe` and response type `outTpe` (codecs summoned at the derive site).
    */
  private def route(using
      q: Quotes,
  )(
      engine: String,
      inst: q.reflect.Term,
      m: q.reflect.Symbol,
      nm: String,
      inTpe: q.reflect.TypeRepr,
      outTpe: q.reflect.TypeRepr,
  ): Expr[InvokeRoute] =
    import q.reflect.*
    inTpe.asType match
      case '[qt] =>
        outTpe.asType match
          case '[rt] =>
            val handler = Lambda(
              Symbol.spliceOwner,
              MethodType(List("req"))(_ => List(inTpe), _ => outTpe),
              (lam, args) =>
                MacroSupport
                  .callSummoning(engine, inst, m, Some(args.head.asInstanceOf[Term]))
                  .changeOwner(lam),
            ).asExprOf[qt => rt]
            val qCodec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[qt]]).asExprOf[JsonCodec[qt]]
            val rCodec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[rt]]).asExprOf[JsonCodec[rt]]
            '{
              Forwarders.invocationRoute[qt, rt](
                InvokeMethodName(${ Expr(nm) }),
                ${ handler },
                ${ qCodec },
                ${ rCodec },
              )
            }

  private def deriveImpl[T: Type](using Quotes): Expr[List[InvokeRoute]] =
    import quotes.reflect.*
    val engine = "InvokeRoutes"
    val inst = MacroSupport.instanceOf[T]
    val methods = MacroSupport.handlerMethods[T]
    if methods.isEmpty then
      report.errorAndAbort(s"$engine.derive: ${TypeRepr.of[T].typeSymbol.name} has no handler methods to derive.")

    val routes = methods.map { m =>
      val inTpe = MacroSupport.valueParamType(m).getOrElse(TypeRepr.of[Unit])
      route(engine, inst, m, MacroSupport.wireName(m), inTpe, MacroSupport.resultTypeOf(m))
    }
    Expr.ofList(routes)

  private def deriveCheckedImpl[Contract: Type, Impl: Type](using Quotes): Expr[List[InvokeRoute]] =
    import quotes.reflect.*
    val engine = "InvokeRoutes"
    val inst = MacroSupport.instanceOf[Impl]
    val implName = TypeRepr.of[Impl].typeSymbol.name.stripSuffix("$")
    val implMethods = MacroSupport.handlerMethods[Impl]

    val routes = MacroSupport.contractMethods[Contract](engine).map { cm =>
      val implM = MacroSupport.requireImplMethod(engine, cm, implMethods, implName)
      val contractIn = MacroSupport.bodyParamType(cm, invokeKnobs)
      val contractOut = MacroSupport.resultTypeOf(cm)
      MacroSupport.checkInOut(
        engine,
        cm,
        implName,
        contractIn,
        contractOut,
        MacroSupport.valueParamType(implM),
        MacroSupport.resultTypeOf(implM),
      )
      // Types verified equal; the contract is the wire-protocol authority, so its name + types win.
      route(engine, inst, implM, MacroSupport.wireName(cm), contractIn.getOrElse(TypeRepr.of[Unit]), contractOut)
    }
    Expr.ofList(routes)
