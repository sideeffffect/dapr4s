package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Server-side reification of workflow activities: turn a plain class into registrable [[dapr4s.WorkflowActivity]]
  * values.
  *
  * `derive[C]` inspects class `C`'s methods that take a `(using DaprCapability)` clause and builds one
  * [[dapr4s.WorkflowActivity]] per method, ready to drop into [[dapr4s.DaprApp.activities]]. The method name maps
  * verbatim (override with [[name `@name`]]); each activity is registered under a stable name derived from the class
  * and method (`<fully-qualified-class>#<method>`), matched by [[WorkflowActivityCalls]] on the calling side.
  *
  * This removes the per-activity `extends WorkflowActivity[I, O]` / `execute` boilerplate and the manual registration.
  *
  * '''Contract.'''
  *   - `C` must be a class with a no-argument primary constructor (the macro creates one shared `new C` instance).
  *   - Each activity method has shape `def m(input: I)(using DaprCapability, …): O` or
  *     `def m()(using DaprCapability, …): O` (input defaults to `Unit` when there is no value parameter). The `using`
  *     clause must contain a `DaprCapability` (threaded in per call); any other `using` parameters — typically the
  *     `JsonCodec`s the body needs for nested Dapr calls — are summoned at the `derive` call site and passed in.
  *   - `JsonCodec[I]` and `JsonCodec[O]` must be resolvable at the `derive` call site — they are summoned there, not
  *     declared on the method.
  *
  * {{{
  *   class CounterActivities:
  *     def add(input: IncrRequest)(using DaprCapability): CounterState = CounterState(input.amount * 2)
  *     def reset()(using DaprCapability): CounterState                 = CounterState(0)
  *
  *   DaprApp(activities = WorkflowActivities.derive[CounterActivities])
  * }}}
  */
@scala.caps.assumeSafe
object WorkflowActivities:

  /** Derive the registrable [[dapr4s.WorkflowActivity]] values defined by class `C`.
    *
    * Each method's Scala name (overridable with [[name `@name`]]) does not become the activity's registration name on
    * its own: the activity is registered under the stable, fully-qualified name `<C-full-name>#<method>` — e.g.
    * `def add` on `com.acme.CounterActivities` registers `"com.acme.CounterActivities#add"`. [[WorkflowActivityCalls]]
    * computes the very same string from `C`, so the two sides always dispatch to each other. The class is named only
    * through this scheme, so `derive` takes no argument.
    */
  inline def derive[C]: List[WorkflowActivity[?, ?]] = ${ deriveImpl[C] }

  private def deriveImpl[C: Type](using Quotes): Expr[List[WorkflowActivity[?, ?]]] =
    import quotes.reflect.*
    val engine = "WorkflowActivities"
    val cSym = TypeRepr.of[C].typeSymbol

    if cSym.flags.is(Flags.Trait) then
      report.errorAndAbort(s"$engine.derive expects a class, but ${cSym.fullName} is a trait.")

    val ctorParams = cSym.primaryConstructor.paramSymss.flatten.filter(_.isTerm)
    if ctorParams.nonEmpty then
      report.errorAndAbort(s"$engine.derive: ${cSym.name} must have a no-argument constructor.")

    val newC = Apply(Select(New(TypeTree.of[C]), cSym.primaryConstructor), Nil).asExprOf[C]

    '{
      val inst: C = ${ newC }
      ${ activitiesFor[C]('inst) }
    }

  private def activitiesFor[C: Type](inst: Expr[C])(using Quotes): Expr[List[WorkflowActivity[?, ?]]] =
    import quotes.reflect.*
    val engine = "WorkflowActivities"
    val daprTpe = TypeRepr.of[DaprCapability]
    val cSym = TypeRepr.of[C].typeSymbol
    val instTerm = inst.asTerm

    def fail(m: Symbol, msg: String): Nothing = MacroSupport.fail(engine, m, msg)

    def termClauses(dd: DefDef): List[TermParamClause] =
      dd.paramss.collect { case tc: TermParamClause => tc }

    def usesDapr(dd: DefDef): Boolean =
      termClauses(dd).exists(_.params.exists(_.tpt.tpe <:< daprTpe))

    val methods = cSym.declaredMethods.filter { m =>
      !m.isClassConstructor && (m.tree match
        case dd: DefDef => usesDapr(dd)
        case _          => false)
    }

    if methods.isEmpty then
      report.errorAndAbort(s"$engine.derive: ${cSym.name} has no `(using DaprCapability)` activity methods to derive.")

    // first non-given value parameter, if any
    def inputType(dd: DefDef): Option[TypeRepr] =
      termClauses(dd).flatMap(_.params).collectFirst {
        case p if !p.symbol.flags.is(Flags.Given) && !(p.tpt.tpe <:< daprTpe) => p.tpt.tpe
      }

    // A given of `tpe`, resolved at the macro-expansion (derive call) site.
    def summonGiven(m: Symbol, tpe: TypeRepr): Term =
      tpe.asType match
        case '[t] =>
          Expr
            .summon[t]
            .map(_.asTerm)
            .getOrElse(fail(m, s"no given instance for ${tpe.show} in scope at the derive site."))

    // build `inst.m(<in?>)(using <dapr>, <summoned givens…>)`, applying clauses in declaration order.
    // The DaprCapability in the `using` clause is threaded from `execute`; any other given params
    // (typically JsonCodecs the body needs for nested Dapr calls) are summoned at the derive site.
    def callTerm(m: Symbol, dd: DefDef, inRef: Option[Term], daprRef: Term): Term =
      termClauses(dd).foldLeft(Select(instTerm, m): Term) { (acc, tc) =>
        val isUsing = tc.params.nonEmpty && tc.params.forall(_.symbol.flags.is(Flags.Given))
        val args =
          if isUsing then tc.params.map(p => if p.tpt.tpe <:< daprTpe then daprRef else summonGiven(m, p.tpt.tpe))
          else if tc.params.isEmpty then Nil
          else if tc.params.sizeIs == 1 then List(inRef.getOrElse(fail(m, "missing input argument.")))
          else fail(m, "an activity method takes at most one request-body parameter.")
        Apply(acc, args)
      }

    val activityExprs = methods.map { m =>
      val dd = m.tree.asInstanceOf[DefDef]
      val inTpe = inputType(dd).getOrElse(TypeRepr.of[Unit])
      val outTpe = dd.returnTpt.tpe
      val nm = MacroSupport.activityName(cSym, m)

      def codec[X: Type]: Expr[JsonCodec[X]] =
        Expr.summon[JsonCodec[X]].getOrElse(fail(m, s"no JsonCodec in scope for ${TypeRepr.of[X].show}."))

      inTpe.asType match
        case '[i] =>
          outTpe.asType match
            case '[o] =>
              // Build the handler as a quote (clean inferred type — no asExprOf of a synthesised
              // function type, no asInstanceOf in the expanded safe-mode code). `in`/`d` are the
              // lambda's parameters, threaded into `inst.m(in)(using d)` via the splice.
              val handler = '{ (in: i, d: DaprCapability) =>
                ${ callTerm(m, dd, Some('in.asTerm), 'd.asTerm).asExprOf[o] }
              }
              '{
                Forwarders.workflowActivity[i, o](
                  ${ Expr(nm) },
                  ${ handler },
                  ${ codec[i] },
                  ${ codec[o] },
                ): WorkflowActivity[?, ?]
              }
    }

    Expr.ofList(activityExprs)
