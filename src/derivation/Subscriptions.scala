package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Server-side derivation of [[dapr4s.Subscription]]s from a handler type.
  *
  * `derive[T](pubsubName)` turns each handler method of `T` (an `object`, or a class with a no-arg constructor) into a
  * `Subscription` on `pubsubName`: the method name maps verbatim (override with [[name `@name`]]) to the
  * [[dapr4s.Topic]], the handler takes a `CloudEvent[Payload]` and returns a [[dapr4s.SubscriptionResult]], and
  * [[deadLetter `@deadLetter`]] sets the dead-letter topic. The handler's `using` capabilities/codecs and the
  * subscription's codec are resolved from the ambient scope at the `derive` call site.
  *
  * {{{
  *   object ResultRoutes:
  *     @name("scan-completed") def onScanCompleted(e: CloudEvent[ScanResult])(using StateCapability, JsonCodec[ScanResult]): SubscriptionResult = ...
  *
  *   DaprApp(subscriptions = Subscriptions.derive[ResultRoutes.type](PubSubName("pubsub")))
  * }}}
  */
@scala.caps.assumeSafe
object Subscriptions:

  inline def derive[T](pubsubName: PubSubName): List[Subscription] = ${ deriveImpl[T]('pubsubName) }

  private def deriveImpl[T: Type](pubsubName: Expr[PubSubName])(using Quotes): Expr[List[Subscription]] =
    import quotes.reflect.*
    val engine = "Subscriptions"
    val inst = MacroSupport.instanceOf[T]
    val methods = MacroSupport.handlerMethods[T]
    val cloudEventSym = Symbol.requiredClass("dapr4s.CloudEvent")
    val deadLetterTpe = TypeRepr.of[deadLetter]
    if methods.isEmpty then
      report.errorAndAbort(s"$engine.derive: ${TypeRepr.of[T].typeSymbol.name} has no handler methods to derive.")

    def fail(m: Symbol, msg: String): Nothing = MacroSupport.fail(engine, m, msg)

    def deadLetterOf(m: Symbol): Expr[Option[Topic]] =
      m.annotations.collectFirst {
        case Apply(Select(New(tpt), _), List(Literal(StringConstant(s)))) if tpt.tpe =:= deadLetterTpe => s
      } match
        case Some(s) => '{ Some(Topic(${ Expr(s) })) }
        case None    => '{ None }

    val routes: List[Expr[Subscription]] = methods.map { m =>
      val nm = MacroSupport.wireName(m)
      val evTpe =
        MacroSupport.valueParamType(m).getOrElse(fail(m, "a subscription handler needs a CloudEvent[T] parameter."))
      val payloadTpe = evTpe.dealias match
        case AppliedType(tc, List(t)) if tc.typeSymbol == cloudEventSym => t
        case _ => fail(m, "the handler parameter must be CloudEvent[T].")
      val dl = deadLetterOf(m)
      payloadTpe.asType match
        case '[t] =>
          val handler = Lambda(
            Symbol.spliceOwner,
            MethodType(List("event"))(_ => List(evTpe), _ => TypeRepr.of[SubscriptionResult]),
            (lam, args) =>
              MacroSupport.callSummoning(engine, inst, m, Some(args.head.asInstanceOf[Term])).changeOwner(lam),
          ).asExprOf[CloudEvent[t] => SubscriptionResult]
          val codec = MacroSupport.summonExpr(TypeRepr.of[JsonCodec[t]]).asExprOf[JsonCodec[t]]
          '{
            Forwarders.subscriptionRoute[t](
              ${ pubsubName },
              Topic(${ Expr(nm) }),
              ${ dl },
              ${ handler },
              ${ codec },
            )
          }
    }
    Expr.ofList(routes)
