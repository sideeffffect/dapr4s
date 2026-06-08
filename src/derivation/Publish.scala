package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.PublishCapability]] publisher facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to a [[dapr4s.Topic]]; the pub/sub component is fixed
  * by the per-call `PublishCapability`. Each method takes the event as its first value parameter, an optional
  * `metadata: Map[MetadataKey, MetadataValue]` knob, and returns `Unit`.
  *
  * {{{
  *   trait OrderEvents:
  *     def orders(event: OrderEvent)(using PublishCapability, JsonCodec[OrderEvent]): Unit
  *   lazy val OrderEvents: OrderEvents = Publish.derive[OrderEvents]
  * }}}
  */
@scala.caps.assumeSafe
object Publish:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the [[dapr4s.Topic]] it publishes to — `def orders` publishes to topic `"orders"` —
    * overridable per method with [[name `@name`]]. The pub/sub component itself is fixed by the per-call
    * [[dapr4s.PublishCapability]], so `derive` takes no argument.
    *
    * {{{
    *   trait OrderEvents:
    *     def orders(event: OrderEvent)(using PublishCapability, JsonCodec[OrderEvent]): Unit
    *   lazy val OrderEvents: OrderEvents = Publish.derive[OrderEvents]
    *
    *   DaprCapability.publish(PubSubName("pubsub")) {
    *     OrderEvents.orders(OrderEvent(...)) // → publish to Topic("orders")
    *   }
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Publish"
    val metadataTpe = TypeRepr.of[Map[MetadataKey, MetadataValue]]
    val capTpe = TypeRepr.of[PublishCapability]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      if !MacroSupport.isUnit(resTpe) then fail("a publish method must return Unit.")

      val metaRef = values.collectFirst {
        case (n, r, t, _) if n == "metadata" =>
          if !(t =:= metadataTpe) then fail("parameter `metadata` must have type Map[MetadataKey, MetadataValue].")
          r
      }
      val bodyEntry = values.headOption.filterNot(_._1 == "metadata")
      values.foreach { case (n, _, _, _) =>
        if n != "metadata" && !bodyEntry.exists(_._1 == n) then
          fail(s"unexpected parameter `$n`; only the event and `metadata` are allowed.")
      }
      val (_, bodyRef, dataTpe, _) = bodyEntry.getOrElse(fail("a publish method needs an event parameter."))

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a PublishCapability."))
        .asExprOf[PublishCapability]

      val codecRef = givens
        .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= dataTpe) => r }
        .getOrElse(fail(s"the `using` clause must provide a JsonCodec for the event (JsonCodec[${dataTpe.show}])."))

      val nm = MacroSupport.wireName(origSym)
      dataTpe.asType match
        case '[t] =>
          val codecExpr = codecRef.asExprOf[JsonCodec[t]]
          metaRef match
            case None =>
              '{
                Forwarders.publish[t](
                  ${ capExpr },
                  Topic(${ Expr(nm) }),
                  ${ bodyRef.asExprOf[t] },
                  ${ codecExpr },
                )
              }.asTerm
            case Some(mr) =>
              '{
                Forwarders.publishMeta[t](
                  ${ capExpr },
                  Topic(${ Expr(nm) }),
                  ${ bodyRef.asExprOf[t] },
                  ${ mr.asExprOf[Map[MetadataKey, MetadataValue]] },
                  ${ codecExpr },
                )
              }.asTerm
    }
