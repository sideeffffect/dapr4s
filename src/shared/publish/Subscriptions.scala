package dapr4s.publish

import dapr4s.derivation.*

import dapr4s.*
import dapr4s.publish.*
import scala.quoted.*

/** Server-side derivation of [[dapr4s.Subscription]]s from a handler type.
  *
  * `derive[T](pubsubName)` turns each handler method of `T` (an `object`, or a class with a no-arg constructor) into a
  * `Subscription` on `pubsubName`: the method name maps verbatim (override with [[name `@name`]]) to the
  * [[dapr4s.Topic]], the handler takes a `CloudEvent[Payload]` and returns a [[dapr4s.SubscriptionResult]], and
  * [[deadLetter `@deadLetter`]] sets the dead-letter topic. The handler's `using` capabilities/codecs and the
  * subscription's codec are resolved from the ambient scope at the `derive` call site.
  *
  * The [[dapr4s.PubSubName]] is either given explicitly (`derive[T](pubsubName)`) or, with the no-argument `derive[T]`,
  * taken from `T`'s simple name (override with `@name` on the type).
  *
  * {{{
  *   object ResultRoutes:
  *     @name("scan-completed") def onScanCompleted(e: CloudEvent[ScanResult])(using StateCapability, JsonCodec[ScanResult]): SubscriptionResult = ...
  *
  *   DaprApp(subscriptions = Subscriptions.derive[ResultRoutes.type](PubSubName("pubsub")))
  * }}}
  *
  * '''Dual.''' [[Publish]] is the outbound counterpart. Use [[deriveChecked]] to bind subscribers to the same publisher
  * `Contract` trait that `Publish.derive[Contract]` turns into publishes, matched by [[dapr4s.Topic]].
  */
@scala.caps.assumeSafe
object Subscriptions:

  /** Derive the [[dapr4s.Subscription]]s of handler type `T` on the given `pubsubName`.
    *
    * Each handler method's Scala name is the [[dapr4s.Topic]] it subscribes to — `def onOrder` subscribes to topic
    * `"onOrder"` — overridable per method with [[name `@name`]]; [[deadLetter `@deadLetter`]] sets the dead-letter
    * topic. This overload names the [[dapr4s.PubSubName]] explicitly; the no-argument overload derives it from `T`'s
    * name instead.
    *
    * {{{
    *   object ResultRoutes:
    *     @name("scan-completed") @deadLetter("scan-failed")
    *     def onScanCompleted(e: CloudEvent[ScanResult])(using StateCapability, JsonCodec[ScanResult]): SubscriptionResult = ...
    *
    *   // subscribes Topic("scan-completed") on PubSubName("pubsub"), dead-lettering to Topic("scan-failed"):
    *   DaprApp(subscriptions = Subscriptions.derive[ResultRoutes.type](PubSubName("pubsub")))
    * }}}
    */
  inline def derive[T](pubsubName: PubSubName): List[Subscription] = ${ deriveImpl[T]('{ Some(pubsubName) }) }

  /** Derive the [[dapr4s.Subscription]]s of handler type `T` on the [[dapr4s.PubSubName]] taken from `T`'s simple name
    * (override with `@name` on the type).
    *
    * Method names map to [[dapr4s.Topic]]s exactly as in the `pubsubName`-taking overload; only the source of the
    * `PubSubName` differs — here it is the type's own name rather than an argument.
    *
    * {{{
    *   @name("pubsub") object ResultRoutes:
    *     def onScanCompleted(e: CloudEvent[ScanResult])(using JsonCodec[ScanResult]): SubscriptionResult = ...
    *
    *   // PubSubName("pubsub") taken from the object's `@name` (else its simple name "ResultRoutes"):
    *   DaprApp(subscriptions = Subscriptions.derive[ResultRoutes.type])
    * }}}
    */
  inline def derive[T]: List[Subscription] = ${ deriveImpl[T]('{ None }) }

  /** Derive the [[dapr4s.Subscription]]s of handler type `Impl`, '''checked''' against publisher contract trait
    * `Contract` on the given `pubsubName`.
    *
    * Same result as [[derive]], but bound to the dual [[Publish]] facade through the shared `Contract` trait. The two
    * sides agree on the [[dapr4s.Topic]] (the wire name — a publisher method and its subscriber are matched by topic,
    * since they name their Scala methods independently), and the macro verifies that for every topic the contract
    * publishes there is an `Impl` handler whose `CloudEvent[Payload]` carries the same payload type. `Impl` stays a
    * plain subscriber: handlers take `CloudEvent[Payload]`, return [[dapr4s.SubscriptionResult]], and may take their
    * own ambient `using` dependencies.
    *
    * {{{
    *   trait OrderEvents:
    *     def orders(event: OrderPlaced)(using PublishCapability, JsonCodec[OrderPlaced]): Unit
    *
    *   object OrderSubscribers:
    *     @name("orders") def onOrder(e: CloudEvent[OrderPlaced]): SubscriptionResult = ...
    *
    *   // checked against OrderEvents; subscribes Topic("orders"):
    *   DaprApp(subscriptions = Subscriptions.deriveChecked[OrderEvents, OrderSubscribers.type](PubSubName("pubsub")))
    * }}}
    *
    * @see
    *   [[Publish.derive]] — the dual publisher facade derived from the same `Contract`.
    */
  inline def deriveChecked[Contract, Impl](pubsubName: PubSubName): List[Subscription] =
    ${ deriveCheckedImpl[Contract, Impl]('{ Some(pubsubName) }) }

  /** Derive the [[dapr4s.Subscription]]s of handler type `Impl`, '''checked''' against publisher contract trait
    * `Contract`, with the [[dapr4s.PubSubName]] taken from `Impl`'s simple name (override with `@name` on the type).
    *
    * @see
    *   [[Publish.derive]] — the dual publisher facade derived from the same `Contract`.
    */
  inline def deriveChecked[Contract, Impl]: List[Subscription] = ${ deriveCheckedImpl[Contract, Impl]('{ None }) }

  private def deriveCheckedImpl[Contract: Type, Impl: Type](
      pubsubNameOpt: Expr[Option[PubSubName]],
  )(using Quotes): Expr[List[Subscription]] =
    crossCheck[Contract, Impl]() // compile-time only; aborts on any publisher/subscriber divergence
    deriveImpl[Impl](pubsubNameOpt)

  /** Verify every topic the `Contract` publishes has an `Impl` subscriber carrying the same payload type. Publisher and
    * subscriber are matched by topic (wire name); the publisher's optional `metadata` knob is not part of the payload.
    */
  private def crossCheck[Contract: Type, Impl: Type]()(using Quotes): Unit =
    import quotes.reflect.*
    val engine = "Subscriptions"
    val implName = TypeRepr.of[Impl].typeSymbol.name.stripSuffix("$")
    val subscribers = MacroSupport.handlerMethods[Impl]

    MacroSupport.contractMethods[Contract](engine).foreach { cm =>
      val topic = MacroSupport.wireName(cm)
      val pubPayload = MacroSupport.bodyParamType(cm, Set("metadata"))
      val subM = MacroSupport.requireImplByWireName(engine, cm, topic, subscribers, implName, "topic")
      val subPayload = MacroSupport.valueParamType(subM).flatMap(MacroSupport.cloudEventArg)
      (pubPayload, subPayload) match
        case (Some(p), Some(s)) if !(p =:= s) =>
          MacroSupport.fail(
            engine,
            cm,
            s"published payload ${p.show} does not match subscriber's CloudEvent[${s.show}] for topic \"$topic\".",
          )
        case (Some(p), None) =>
          MacroSupport.fail(
            engine,
            cm,
            s"publishes ${p.show} on topic \"$topic\", but its subscriber takes no CloudEvent payload.",
          )
        case _ => ()
    }

  private def deriveImpl[T: Type](pubsubNameOpt: Expr[Option[PubSubName]])(using Quotes): Expr[List[Subscription]] =
    import quotes.reflect.*
    val engine = "Subscriptions"
    val derivedName = MacroSupport.derivedTypeName(TypeRepr.of[T].typeSymbol)
    val pubsubName: Expr[PubSubName] = '{ ${ pubsubNameOpt }.getOrElse(PubSubName(${ Expr(derivedName) })) }
    val inst = MacroSupport.instanceOf[T]
    val methods = MacroSupport.handlerMethods[T]
    val cloudEventSym = Symbol.requiredClass("dapr4s.publish.CloudEvent")
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
