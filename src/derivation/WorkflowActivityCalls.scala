package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Caller facade for workflow activities: derive a trait of typed activity calls from an implementation class.
  *
  * `derive[Calls, Impl]` implements each method of trait `Calls` by scheduling the matching activity of class `Impl`
  * (the one reified by [[WorkflowActivities]]) through
  * [[dapr4s.WorkflowContext.callActivityByName(name:* callActivityByName]] by its stable name. The two sides agree on
  * the name because both compute it from `Impl`.
  *
  * The macro '''verifies''' each `Calls` method against `Impl`: there must be an `Impl` method of the same Scala name,
  * and its input type must match (output agreement is enforced by the generated body's return type). This keeps the
  * caller and the implementation bound even though they are separate declarations.
  *
  * '''Contract.''' Each `Calls` method has shape `def m(input: I)(using ctx: WorkflowContext): Task[O]^{ctx}` or
  * `def m()(using ctx: WorkflowContext): Task[O]^{ctx}` — like [[WorkflowEvents]], the returned `Task` captures the
  * per-call context. `JsonCodec[I]`/`JsonCodec[O]` are summoned at the `derive` call site. The corresponding `Impl`
  * method has shape `def m(input: I)(using DaprCapability): O` (see [[WorkflowActivities]]).
  *
  * {{{
  *   trait CounterActivityCalls:
  *     def add(input: IncrRequest)(using ctx: WorkflowContext): Task[CounterState]^{ctx}
  *   object CounterActivityCalls extends WorkflowActivityCalls.Derived[CounterActivityCalls, CounterActivities]
  *
  *   class AddingWorkflow extends Workflow:
  *     def run(using WorkflowContext): Unit =
  *       val acts = CounterActivityCalls.derive
  *       WorkflowContext.complete(acts.add(IncrRequest(21)).await())
  * }}}
  */
object WorkflowActivityCalls:

  inline def derive[Calls, Impl]: Calls = ${ deriveImpl[Calls, Impl] }

  trait Derived[Calls, Impl]:
    inline def derive: Calls = WorkflowActivityCalls.derive[Calls, Impl]

  private def deriveImpl[Calls: Type, Impl: Type](using Quotes): Expr[Calls] =
    import quotes.reflect.*
    val engine = "WorkflowActivityCalls"
    val ctxTpe = TypeRepr.of[WorkflowContext]
    val daprTpe = TypeRepr.of[DaprCapability]
    val implSym = TypeRepr.of[Impl].typeSymbol
    val implMeth = implSym.declaredMethods

    // first non-given, non-DaprCapability value parameter of an impl method
    def implInput(dd: DefDef): Option[TypeRepr] =
      dd.paramss.collect { case tc: TermParamClause => tc }.flatMap(_.params).collectFirst {
        case p if !p.symbol.flags.is(Flags.Given) && !(p.tpt.tpe <:< daprTpe) => p.tpt.tpe
      }

    MacroSupport.deriveTrait[Calls](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      val ctxExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< ctxTpe => r }
        .getOrElse(fail("the `using` clause must provide a WorkflowContext."))
        .asExprOf[WorkflowContext]

      if values.sizeIs > 1 then fail("an activity call takes at most one input parameter.")
      val bodyEntry = values.headOption

      // locate the matching implementation method and validate the input type
      val implM = implMeth
        .find(_.name == origSym.name)
        .getOrElse(fail(s"no method `${origSym.name}` on implementation ${implSym.name}."))
      val implDd = implM.tree.asInstanceOf[DefDef]
      val implIn = implInput(implDd)
      val implOut = implDd.returnTpt.tpe

      (bodyEntry, implIn) match
        case (Some((_, _, callerIn, _)), Some(ii)) if !(callerIn =:= ii) =>
          fail(s"input type ${callerIn.show} does not match ${implSym.name}.${implM.name}'s input ${ii.show}.")
        case (Some(_), None) =>
          fail(s"${implSym.name}.${implM.name} takes no input, but the call declares one.")
        case (None, Some(ii)) =>
          fail(s"${implSym.name}.${implM.name} takes input ${ii.show}, but the call declares none.")
        case _ => ()

      val nm = MacroSupport.activityName(implSym, implM)
      val nameExpr = '{ ActivityName(${ Expr(nm) }) }

      def codecFor(arg: TypeRepr, role: String): Expr[Any] =
        arg.asType match
          case '[t] =>
            Expr
              .summon[JsonCodec[t]]
              .getOrElse(fail(s"no JsonCodec[$role] in scope for ${arg.show}."))

      implOut.asType match
        case '[o] =>
          val oc = codecFor(implOut, "Output").asExprOf[JsonCodec[o]]
          bodyEntry match
            case Some((_, bodyRef, _, _)) =>
              implIn.get.asType match
                case '[i] =>
                  val ic = codecFor(implIn.get, "Input").asExprOf[JsonCodec[i]]
                  '{
                    Forwarders.callActivityByName[i, o](
                      ${ ctxExpr },
                      ${ nameExpr },
                      ${ bodyRef.asExprOf[i] },
                      ${ ic },
                      ${ oc },
                    )
                  }.asTerm
            case None =>
              '{ Forwarders.callActivityByNameNoInput[o](${ ctxExpr }, ${ nameExpr }, ${ oc }) }.asTerm
    }
