package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Server-side reification of a Dapr virtual actor: turn an actor class into a [[dapr4s.ActorDefinition]].
  *
  * `derive[C]` inspects class `C`'s methods (those with a `(using ActorContext)` clause) and builds the
  * `ActorDefinition`/`ActorRoutes` the runtime expects. The method name maps verbatim (override with [[name `@name`]])
  * to the route key; [[reminder `@reminder`]] and [[timer `@timer`]] turn a method into a reminder/timer route (its
  * result is discarded).
  *
  * The actor's [[dapr4s.ActorType]] comes from one of two overloads: `derive[C](actorType)` uses the given type
  * verbatim, while the no-argument `derive[C]` derives it from the class's simple name (override with `@name` on the
  * class).
  *
  * '''Contract.'''
  *   - `C` must be a class with either a no-argument primary constructor or a single `ActorId` constructor parameter
  *     (the macro instantiates `new C(id)` per invocation).
  *   - Each handler method has shape `def m(input: I)(using ActorContext): O` or `def m()(using ActorContext): O`; its
  *     `using` clause must contain exactly an `ActorContext`. The input type defaults to `Unit` when there is no value
  *     parameter.
  *   - `JsonCodec[I]` and `JsonCodec[O]` (just `JsonCodec[I]` for `@reminder`/`@timer`) must be resolvable at the
  *     `derive` call site — they are summoned there, not declared on the method.
  *
  * {{{
  *   class Counter(actorId: ActorId):
  *     def increment(input: IncrRequest)(using ActorContext): CounterState = ...
  *     def get()(using ActorContext): CounterState = ...
  *     @reminder def scheduledReset(msg: String)(using ActorContext): Unit = ...
  *     @timer def autoIncrement(req: IncrRequest)(using ActorContext): Unit = ...
  *
  *   val definition = ActorDefinitions.derive[Counter](ActorType("Counter"))
  *   val sameDefinition = ActorDefinitions.derive[Counter] // ActorType derived from the class's simple name
  * }}}
  *
  * '''Dual.''' [[Actor]] is the client counterpart. Use [[deriveChecked]] to bind an actor class to the same caller
  * `Contract` trait that `Actor.derive[Contract]` turns into invocations (`@reminder`/`@timer` methods are
  * runtime-triggered, so they are outside the caller contract).
  */
@scala.caps.assumeSafe
object ActorDefinitions:

  /** Derive an [[dapr4s.ActorDefinition]] for class `C`, registered under `actorType`.
    *
    * Each handler method's Scala name (overridable with [[name `@name`]]) becomes its route key — an
    * [[dapr4s.ActorMethodName]] for a normal method, or a [[dapr4s.ReminderName]]/[[dapr4s.TimerName]] when the method
    * carries [[reminder `@reminder`]]/[[timer `@timer`]]. This overload names the [[dapr4s.ActorType]] explicitly; the
    * no-argument overload derives it from `C`'s name instead.
    *
    * {{{
    *   class Counter(actorId: ActorId):
    *     def increment(input: IncrRequest)(using ActorContext): CounterState = ...
    *     def get()(using ActorContext): CounterState                         = ...
    *     @reminder def scheduledReset(msg: String)(using ActorContext): Unit = ...
    *
    *   // route keys: ActorMethodName("increment"/"get"), ReminderName("scheduledReset"):
    *   val definition = ActorDefinitions.derive[Counter](ActorType("Counter"))
    * }}}
    */
  inline def derive[C](actorType: ActorType): ActorDefinition = ${ deriveImpl[C]('{ Some(actorType) }) }

  /** Derive an [[dapr4s.ActorDefinition]] for class `C`, with the [[dapr4s.ActorType]] taken from `C`'s simple name
    * (override with `@name` on the class).
    *
    * Method names become route keys exactly as in the `actorType`-taking overload; only the source of the `ActorType`
    * differs — here it is the class's own name rather than an argument.
    *
    * {{{
    *   // ActorType("Counter") taken from the class's simple name (override with `@name` on the class):
    *   val definition = ActorDefinitions.derive[Counter]
    * }}}
    */
  inline def derive[C]: ActorDefinition = ${ deriveImpl[C]('{ None }) }

  /** Derive the [[dapr4s.ActorDefinition]] for actor class `Impl`, '''checked''' against caller contract trait
    * `Contract`, with the [[dapr4s.ActorType]] given explicitly.
    *
    * Same result as [[derive]], but bound to the dual [[Actor]] facade through the shared `Contract` trait: every
    * `Contract` method must be implemented by an `Impl` actor '''method''' (not a `@reminder`/`@timer`, which the
    * runtime — not a caller — triggers) of the same Scala name and matching request/response types. Reminders and
    * timers on `Impl` are derived as usual but are not part of the caller contract.
    *
    * @see
    *   [[Actor.derive]] — the dual client facade derived from the same `Contract`.
    */
  inline def deriveChecked[Contract, Impl](actorType: ActorType): ActorDefinition =
    ${ deriveCheckedImpl[Contract, Impl]('{ Some(actorType) }) }

  /** Derive the [[dapr4s.ActorDefinition]] for actor class `Impl`, '''checked''' against caller contract trait
    * `Contract`, with the [[dapr4s.ActorType]] taken from `Impl`'s simple name (override with `@name` on the class).
    *
    * @see
    *   [[Actor.derive]] — the dual client facade derived from the same `Contract`.
    */
  inline def deriveChecked[Contract, Impl]: ActorDefinition = ${ deriveCheckedImpl[Contract, Impl]('{ None }) }

  private def deriveCheckedImpl[Contract: Type, Impl: Type](
      actorType: Expr[Option[ActorType]],
  )(using Quotes): Expr[ActorDefinition] =
    crossCheck[Contract, Impl]() // compile-time only; aborts on any caller/impl divergence
    deriveImpl[Impl](actorType)

  /** Verify every `Contract` method is answered by a callable `Impl` actor method (matching name + request/response
    * types). `@reminder`/`@timer` methods are runtime-triggered, so they are excluded from the eligible set.
    */
  private def crossCheck[Contract: Type, Impl: Type]()(using Quotes): Unit =
    import quotes.reflect.*
    val engine = "ActorDefinitions"
    val actorCtx = TypeRepr.of[ActorContext]
    val reminderTpe = TypeRepr.of[reminder]
    val timerTpe = TypeRepr.of[timer]
    val implSym = TypeRepr.of[Impl].typeSymbol
    val implName = implSym.name.stripSuffix("$")

    val callable = implSym.declaredMethods.filter { m =>
      !m.isClassConstructor && !m.annotations.exists(a => a.tpe =:= reminderTpe || a.tpe =:= timerTpe) && (m.tree match
        case dd: DefDef =>
          dd.paramss.collect { case tc: TermParamClause => tc }.exists(_.params.exists(_.tpt.tpe <:< actorCtx))
        case _ => false)
    }

    MacroSupport.contractMethods[Contract](engine).foreach { cm =>
      val implM = MacroSupport.requireImplMethod(engine, cm, callable, implName)
      MacroSupport.checkInOut(
        engine,
        cm,
        implName,
        MacroSupport.bodyParamType(cm, Set.empty),
        MacroSupport.resultTypeOf(cm),
        MacroSupport.valueParamType(implM),
        MacroSupport.resultTypeOf(implM),
      )
    }

  private def deriveImpl[C: Type](actorType: Expr[Option[ActorType]])(using Quotes): Expr[ActorDefinition] =
    import quotes.reflect.*
    val typeName = MacroSupport.derivedTypeName(TypeRepr.of[C].typeSymbol)
    '{
      ActorDefinition(${ actorType }.getOrElse(ActorType(${ Expr(typeName) }))) {
        (id: ActorId) => (ctx: ActorContext) ?=> ${ routesFor[C]('id, 'ctx) }
      }
    }

  private def routesFor[C: Type](id: Expr[ActorId], ctx: Expr[ActorContext])(using Quotes): Expr[ActorRoutes] =
    import quotes.reflect.*
    val cSym = TypeRepr.of[C].typeSymbol
    val ctor = cSym.primaryConstructor
    val params = ctor.paramSymss.flatten.filter(_.isTerm)
    val newC = New(TypeTree.of[C])
    val instTerm =
      params match
        case Nil      => Apply(Select(newC, ctor), Nil)
        case p :: Nil => Apply(Select(newC, ctor), List(id.asTerm))
        case _        =>
          report.errorAndAbort(
            s"ActorDefinitions.derive: ${cSym.name} must have a no-arg or single-ActorId constructor.",
          )
    instTerm.asExprOf[C] match
      case '{ $inst: C } => assembleRoutes[C](inst, ctx)

  private def assembleRoutes[C: Type](inst: Expr[C], ctx: Expr[ActorContext])(using Quotes): Expr[ActorRoutes] =
    import quotes.reflect.*
    val engine = "ActorDefinitions"
    val actorCtx = TypeRepr.of[ActorContext]
    val reminderTpe = TypeRepr.of[reminder]
    val timerTpe = TypeRepr.of[timer]
    val cSym = TypeRepr.of[C].typeSymbol
    val instTerm = inst.asTerm
    val ctxTerm = ctx.asTerm

    def hasAnnot(m: Symbol, tpe: TypeRepr): Boolean =
      m.annotations.exists(_.tpe =:= tpe)

    // term clauses of a method, as (param-types, isUsingActorContext)
    def termClauses(dd: DefDef): List[(List[(String, TypeRepr)], Boolean)] =
      dd.paramss.collect { case tc: TermParamClause =>
        val ps = tc.params.map(p => (p.name, p.tpt.tpe))
        val isCtx = tc.params.exists(_.tpt.tpe <:< actorCtx)
        (ps, isCtx)
      }

    val methods = cSym.declaredMethods.filter { m =>
      !m.isClassConstructor && (m.tree match
        case dd: DefDef => termClauses(dd).exists(_._2)
        case _          => false)
    }

    def fail(m: Symbol, msg: String): Nothing = MacroSupport.fail(engine, m, msg)

    // Build the term `inst.m(<in?>)(using ctx)`, applying clauses in declaration order.
    def callTerm(m: Symbol, dd: DefDef, inRef: Option[Term]): Term =
      val clauses = dd.paramss.collect { case tc: TermParamClause => tc }
      clauses.foldLeft(Select(instTerm, m): Term) { (acc, tc) =>
        val isCtx = tc.params.exists(_.tpt.tpe <:< actorCtx)
        val args =
          if isCtx then
            if tc.params.sizeIs != 1 then fail(m, "the `using` clause must contain exactly an ActorContext.")
            List(ctxTerm)
          else if tc.params.isEmpty then Nil
          else if tc.params.sizeIs == 1 then List(inRef.getOrElse(fail(m, "missing input argument.")))
          else fail(m, "an actor method takes at most one request-body parameter.")
        Apply(acc, args)
      }

    def inputType(dd: DefDef): Option[TypeRepr] =
      termClauses(dd).collectFirst { case (ps, false) if ps.sizeIs == 1 => ps.head._2 }

    // Build a `I => R` (or `Unit => R`) handler lambda that calls the actor method.
    def handlerLambda(m: Symbol, dd: DefDef, inTpe: TypeRepr, outTpe: TypeRepr, discard: Boolean): Term =
      val hasInput = inputType(dd).isDefined
      val mt = MethodType(List("in"))(_ => List(inTpe), _ => if discard then TypeRepr.of[Unit] else outTpe)
      Lambda(
        Symbol.spliceOwner,
        mt,
        (lam, args) =>
          val inRef = if hasInput then Some(args.head.asInstanceOf[Term]) else None
          val call = callTerm(m, dd, inRef).changeOwner(lam)
          if discard then Block(List(call), Literal(UnitConstant())) else call,
      )

    val methodRoutes = scala.collection.mutable.ListBuffer.empty[Expr[ActorMethodRoute]]
    val reminderRoutes = scala.collection.mutable.ListBuffer.empty[Expr[ActorReminderRoute]]
    val timerRoutes = scala.collection.mutable.ListBuffer.empty[Expr[ActorTimerRoute]]

    methods.foreach { m =>
      val dd = m.tree.asInstanceOf[DefDef]
      val nm = MacroSupport.wireName(m)
      val inTpe = inputType(dd).getOrElse(TypeRepr.of[Unit])
      val outTpe = dd.returnTpt.tpe
      val isReminder = hasAnnot(m, reminderTpe)
      val isTimer = hasAnnot(m, timerTpe)

      def codec[X: Type]: Expr[JsonCodec[X]] =
        Expr.summon[JsonCodec[X]].getOrElse(fail(m, s"no JsonCodec in scope for ${TypeRepr.of[X].show}."))

      inTpe.asType match
        case '[i] =>
          if isReminder then
            val h = handlerLambda(m, dd, inTpe, outTpe, discard = true).asExprOf[i => Unit]
            reminderRoutes += '{ Forwarders.actorReminderRoute[i](ReminderName(${ Expr(nm) }), ${ h }, ${ codec[i] }) }
          else if isTimer then
            val h = handlerLambda(m, dd, inTpe, outTpe, discard = true).asExprOf[i => Unit]
            timerRoutes += '{ Forwarders.actorTimerRoute[i](TimerName(${ Expr(nm) }), ${ h }, ${ codec[i] }) }
          else
            outTpe.asType match
              case '[o] =>
                val h = handlerLambda(m, dd, inTpe, outTpe, discard = false).asExprOf[i => o]
                methodRoutes += '{
                  Forwarders
                    .actorMethodRoute[i, o](ActorMethodName(${ Expr(nm) }), ${ h }, ${ codec[i] }, ${ codec[o] })
                }
    }

    '{
      ActorRoutes(
        methods = ${ Expr.ofList(methodRoutes.toList) },
        reminders = ${ Expr.ofList(reminderRoutes.toList) },
        timers = ${ Expr.ofList(timerRoutes.toList) },
      )
    }
