package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.ActorCapability]] client facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to an [[dapr4s.ActorMethodName]]; the target actor
  * instance is fixed by the per-call `ActorCapability` (from `DaprCapability.actor(type, id)`), so `derive` takes no
  * argument.
  *
  *   - body + non-`Unit` result → `invoke(method, data)[Resp]`
  *   - no body + non-`Unit` result → `invoke[Resp](method)`
  *   - no body + `Unit` result → `invokeVoid(method)`
  *
  * A `Unit`-returning method with a request body is rejected (there is no such actor overload).
  */
@scala.caps.assumeSafe
object Actor:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the [[dapr4s.ActorMethodName]] it invokes — `def increment` calls the actor method
    * `"increment"` — overridable per method with [[name `@name`]]. The name addresses only the method: the target actor
    * instance is fixed by the [[dapr4s.ActorCapability]] each method receives in its `using` clause (from
    * `DaprCapability.actor(type, id)`), so `derive` itself takes no argument.
    *
    * {{{
    *   trait CounterActor:
    *     def increment(req: IncrRequest)(using ActorCapability, JsonCodec[IncrRequest], JsonCodec[CounterState]): CounterState
    *     def get()(using ActorCapability, JsonCodec[CounterState]): CounterState
    *     def reset()(using ActorCapability): Unit
    *   lazy val CounterActor: CounterActor = Actor.derive[CounterActor]
    *
    *   // choose the target instance, then call methods by their Scala name:
    *   DaprCapability.actor(ActorType("Counter"), ActorId("c-1")) {
    *     CounterActor.increment(IncrRequest(1)) // → invoke("increment", …)[CounterState]
    *     CounterActor.get()                     // → invoke[CounterState]("get")
    *     CounterActor.reset()                   // → invokeVoid("reset")
    *   }
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Actor"
    val capTpe = TypeRepr.of[ActorCapability]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      if values.sizeIs > 1 then fail("an actor method takes at most one request-body parameter.")
      val bodyEntry = values.headOption

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide an ActorCapability."))
        .asExprOf[ActorCapability]

      def codecFor(arg: TypeRepr, role: String): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[$role] (JsonCodec[${arg.show}])."))

      val nm = MacroSupport.wireName(origSym)
      val methodExpr = '{ ActorMethodName(${ Expr(nm) }) }

      (bodyEntry, MacroSupport.isUnit(resTpe)) match
        case (None, true) =>
          '{ Forwarders.actorInvokeVoid(${ capExpr }, ${ methodExpr }) }.asTerm
        case (Some(_), true) =>
          fail("an actor method returning Unit must take no request body (it maps to invokeVoid).")
        case (None, false) =>
          resTpe.asType match
            case '[resp] =>
              val respCodec = codecFor(resTpe, "Resp").asExprOf[JsonCodec[resp]]
              '{ Forwarders.actorInvokeNoBody[resp](${ capExpr }, ${ methodExpr }, ${ respCodec }) }.asTerm
        case (Some((_, bodyRef, reqTpe, _)), false) =>
          reqTpe.asType match
            case '[req] =>
              resTpe.asType match
                case '[resp] =>
                  val reqCodec = codecFor(reqTpe, "Req").asExprOf[JsonCodec[req]]
                  val respCodec = codecFor(resTpe, "Resp").asExprOf[JsonCodec[resp]]
                  '{
                    Forwarders.actorInvokeBody[req, resp](
                      ${ capExpr },
                      ${ methodExpr },
                      ${ bodyRef.asExprOf[req] },
                      ${ reqCodec },
                      ${ respCodec },
                    )
                  }.asTerm
    }
