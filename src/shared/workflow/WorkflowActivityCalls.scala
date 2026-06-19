package dapr4s.workflow

import dapr4s.derivation.*

import dapr4s.*
import dapr4s.workflow.*
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
  * `JsonCodec[I]`/`JsonCodec[O]` are summoned where `derive` is expanded, so call it inside the workflow body (where
  * the workflow's codec givens are in scope) rather than from a top-level val:
  * {{{
  *   trait CounterActivityCalls:
  *     def add(input: IncrRequest)(using ctx: WorkflowContext): Task[CounterState]^{ctx}
  *
  *   class AddingWorkflow extends Workflow:
  *     def run(using WorkflowContext): Unit =
  *       val acts = WorkflowActivityCalls.derive[CounterActivityCalls, CounterActivities]
  *       WorkflowContext.complete(acts.add(IncrRequest(21)).await())
  * }}}
  */
@scala.caps.assumeSafe
object WorkflowActivityCalls:

  /** Derive a caller facade for trait `Calls`, backed by the activities of class `Impl`.
    *
    * Here the Scala method name is a '''local handle''', not itself a wire name: each `Calls` method selects the `Impl`
    * method of the same Scala name — `def add` binds to `Impl.add` — and the call is then dispatched by the stable
    * activity name both sides compute from `Impl`, `<Impl-full-name>#<method>` (so an `@name` on the `Impl` method, not
    * on `Calls`, shifts the wire name). The macro verifies the matching `Impl` method exists and that its input type
    * agrees, keeping caller and implementation bound across their separate declarations.
    *
    * {{{
    *   trait CounterActivityCalls:
    *     def add(input: IncrRequest)(using ctx: WorkflowContext): Task[CounterState]^{ctx}
    *
    *   class AddingWorkflow extends Workflow:
    *     def run(using WorkflowContext): Unit =
    *       // `add` binds to CounterActivities.add and dispatches "…CounterActivities#add":
    *       val acts = WorkflowActivityCalls.derive[CounterActivityCalls, CounterActivities]
    *       WorkflowContext.complete(acts.add(IncrRequest(21)).await())
    * }}}
    */
  inline def derive[Calls, Impl]: Calls = ${ deriveImpl[Calls, Impl](false) }

  /** Like [[derive]], but additionally '''verifies''' each `Calls` method against `Impl`: there must be an `Impl`
    * method of the same Scala name whose input type matches (output agreement is enforced by the generated body's
    * return type). `derive` binds the two only by name (enough to compute the dispatch string); `deriveChecked` also
    * proves the request types line up, so a caller/implementation drift fails at compile time.
    *
    * @see
    *   [[WorkflowActivities.deriveChecked]] — the dual server reification checked against the same `Calls` trait.
    */
  inline def deriveChecked[Calls, Impl]: Calls = ${ deriveImpl[Calls, Impl](true) }

  private def deriveImpl[Calls: Type, Impl: Type](verify: Boolean)(using Quotes): Expr[Calls] =
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

      if verify then
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
            case Some((_, bodyRef, callerIn, _)) =>
              // Encode with the caller's own declared input type (it equals `implIn` once `deriveChecked` has
              // verified it; `derive` skips that check, so do not assume `implIn` is present here).
              callerIn.asType match
                case '[i] =>
                  val ic = codecFor(callerIn, "Input").asExprOf[JsonCodec[i]]
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
