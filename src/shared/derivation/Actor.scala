package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of actor client facades from a trait — covering both invoking actor methods and scheduling the actor's
  * reminders and timers.
  *
  * A '''plain''' method forwards to [[dapr4s.ActorCapability]]; its name maps verbatim (override with [[name `@name`]])
  * to an [[dapr4s.ActorMethodName]], with the target instance fixed by the per-call `ActorCapability` (from
  * `DaprCapability.actor(type, id)`):
  *
  *   - body + non-`Unit` result → `invoke(method, data)[Resp]`
  *   - no body + non-`Unit` result → `invoke[Resp](method)`
  *   - no body + `Unit` result → `invokeVoid(method)`
  *
  * A `Unit`-returning plain method with a request body is rejected (there is no such actor overload).
  *
  * A [[reminder `@reminder`]] or [[timer `@timer`]] method instead forwards to [[dapr4s.ActorContext.registerReminder]]
  * / [[dapr4s.ActorContext.registerTimer]]: its name is the [[dapr4s.ReminderName]]/[[dapr4s.TimerName]], its first
  * non-knob value parameter is the scheduled `data`, and a `dueTime: FiniteDuration` (required) and
  * `period: Option[FiniteDuration]` (optional) configure the schedule. Such a method returns `Unit` and takes a
  * `using ActorContext` (these are scheduled from inside the actor).
  *
  * '''Dual.''' [[ActorDefinitions]] is the server counterpart: a plain method invokes a route reified from the actor
  * class, and a `@reminder`/`@timer` scheduling method schedules a callback the class's matching `@reminder`/`@timer`
  * route handles. `ActorDefinitions.deriveChecked[Contract, Impl]` reads the same `Contract` trait and verifies every
  * method — plain, reminder, and timer — is answered by the matching `Impl` actor method with the same payload types.
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
    *     // scheduling: forwards to ActorContext.registerReminder / registerTimer
    *     @reminder def scheduledReset(msg: String, dueTime: FiniteDuration)(using ActorContext, JsonCodec[String]): Unit
    *     @timer def autoIncrement(req: IncrRequest, dueTime: FiniteDuration)(using ActorContext, JsonCodec[IncrRequest]): Unit
    *   lazy val CounterActor: CounterActor = Actor.derive[CounterActor]
    *
    *   // invocation: choose the target instance, then call methods by their Scala name:
    *   DaprCapability.actor(ActorType("Counter"), ActorId("c-1")) {
    *     CounterActor.increment(IncrRequest(1)) // → invoke("increment", …)[CounterState]
    *     CounterActor.get()                     // → invoke[CounterState]("get")
    *     CounterActor.reset()                   // → invokeVoid("reset")
    *   }
    *   // scheduling: from inside an actor handler, where the per-instance ActorContext is in scope:
    *   def increment(req: IncrRequest)(using ActorContext): CounterState =
    *     CounterActor.scheduledReset("clear", 1.hour) // → ActorContext.registerReminder(ReminderName("scheduledReset"), …)
    *     ...
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Actor"
    val capTpe = TypeRepr.of[ActorCapability]
    val ctxTpe = TypeRepr.of[ActorContext]
    val reminderTpe = TypeRepr.of[reminder]
    val timerTpe = TypeRepr.of[timer]
    val durTpe = TypeRepr.of[scala.concurrent.duration.FiniteDuration]
    val durOptTpe = TypeRepr.of[Option[scala.concurrent.duration.FiniteDuration]]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      def codecFor(arg: TypeRepr, role: String): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[$role] (JsonCodec[${arg.show}])."))

      val nm = MacroSupport.wireName(origSym)
      val isReminder = origSym.annotations.exists(_.tpe =:= reminderTpe)
      val isTimer = origSym.annotations.exists(_.tpe =:= timerTpe)

      if isReminder || isTimer then
        // Scheduling method: forward to ActorContext.registerReminder/registerTimer. The Scala name is the
        // ReminderName/TimerName; `data` is the first non-knob value parameter; `dueTime`/`period` are the knobs.
        val kind = if isReminder then "reminder" else "timer"
        if !MacroSupport.isUnit(resTpe) then fail(s"a `@$kind` scheduling method must return Unit.")
        val ctxExpr = givens
          .collectFirst { case (_, r, t, _) if t <:< ctxTpe => r }
          .getOrElse(fail(s"a `@$kind` scheduling method's `using` clause must provide an ActorContext."))
          .asExprOf[ActorContext]
        def knob(name: String, expect: TypeRepr): Option[Term] =
          values.collectFirst {
            case (n, r, t, _) if n == name =>
              if !(t =:= expect) then fail(s"parameter `$name` must have type ${expect.show}.")
              r
          }
        val (_, dataRef, dataTpe, _) = values
          .find(v => v._1 != "dueTime" && v._1 != "period")
          .getOrElse(fail(s"a `@$kind` scheduling method needs a data parameter."))
        val dueRef = knob("dueTime", durTpe)
          .getOrElse(fail(s"a `@$kind` scheduling method needs a `dueTime: FiniteDuration` parameter."))
          .asExprOf[scala.concurrent.duration.FiniteDuration]
        val periodExpr = knob("period", durOptTpe)
          .map(_.asExprOf[Option[scala.concurrent.duration.FiniteDuration]])
          .getOrElse('{ None })
        dataTpe.asType match
          case '[d] =>
            val codec = codecFor(dataTpe, "data").asExprOf[JsonCodec[d]]
            val dataExpr = dataRef.asExprOf[d]
            if isReminder then
              '{
                Forwarders.actorRegisterReminder[d](
                  ${ ctxExpr },
                  ReminderName(${ Expr(nm) }),
                  ${ dataExpr },
                  ${ dueRef },
                  ${ periodExpr },
                  ${ codec },
                )
              }.asTerm
            else
              '{
                Forwarders.actorRegisterTimer[d](
                  ${ ctxExpr },
                  TimerName(${ Expr(nm) }),
                  ${ dataExpr },
                  ${ dueRef },
                  ${ periodExpr },
                  ${ codec },
                )
              }.asTerm
      else
        // Invocation method: forward to ActorCapability.invoke / invokeVoid.
        if values.sizeIs > 1 then fail("an actor method takes at most one request-body parameter.")
        val bodyEntry = values.headOption
        val capExpr = givens
          .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
          .getOrElse(fail("the `using` clause must provide an ActorCapability."))
          .asExprOf[ActorCapability]
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
