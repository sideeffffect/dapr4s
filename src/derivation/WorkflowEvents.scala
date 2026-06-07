package dapr4s.derivation

import dapr4s.*
import scala.concurrent.duration.FiniteDuration
import scala.quoted.*

/** Derivation of [[dapr4s.WorkflowContext]] external-event waiters from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to an [[dapr4s.EventName]]. Each method returns the
  * captured `Task[T]^{ctx}` produced by [[dapr4s.WorkflowContext.waitForExternalEvent]]; an optional
  * `timeout: FiniteDuration` parameter selects the timed overload.
  *
  * Because the returned `Task` captures the per-call `WorkflowContext`, the trait method must annotate its result with
  * that context's capture set, e.g.:
  * {{{
  *   trait Events:
  *     def approval(timeout: FiniteDuration)(using ctx: WorkflowContext, c: JsonCodec[Approval]): Task[Approval]^{ctx}
  *   lazy val Events: Events = WorkflowEvents.derive[Events]
  * }}}
  */
object WorkflowEvents:

  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "WorkflowEvents"
    val capTpe = TypeRepr.of[WorkflowContext]
    val durationTpe = TypeRepr.of[FiniteDuration]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      val ctxExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a WorkflowContext."))
        .asExprOf[WorkflowContext]

      val (codecRef, eventTpe) = givens
        .collectFirst {
          case (_, r, t, _) if MacroSupport.jsonCodecArg(t).isDefined => (r, MacroSupport.jsonCodecArg(t).get)
        }
        .getOrElse(fail("the `using` clause must provide a JsonCodec for the event payload."))

      val timeoutRef = values.collectFirst {
        case (n, r, t, _) if n == "timeout" =>
          if !(t =:= durationTpe) then fail("parameter `timeout` must have type FiniteDuration.")
          r
      }
      values.foreach { case (n, _, _, _) =>
        if n != "timeout" then fail(s"unexpected parameter `$n`; an event waiter takes only an optional `timeout`.")
      }

      val nm = MacroSupport.wireName(origSym)
      val eventExpr = '{ EventName(${ Expr(nm) }) }

      eventTpe.asType match
        case '[t] =>
          val codecExpr = codecRef.asExprOf[JsonCodec[t]]
          timeoutRef match
            case Some(to) =>
              '{
                Forwarders.wfWaitEvent[t](
                  ${ ctxExpr },
                  ${ eventExpr },
                  ${ to.asExprOf[FiniteDuration] },
                  ${ codecExpr },
                )
              }.asTerm
            case None =>
              '{ Forwarders.wfWaitEventNoTimeout[t](${ ctxExpr }, ${ eventExpr }, ${ codecExpr }) }.asTerm
    }
