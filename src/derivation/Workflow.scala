package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.WorkflowCapability]] start facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to a [[dapr4s.WorkflowName]]. Each method returns a
  * [[dapr4s.WorkflowInstanceId]]; its value parameters select the overload:
  *   - none → [[dapr4s.WorkflowCapability.start(name:* start(name)]]
  *   - `input` only → `start(name, input)`
  *   - `instanceId: WorkflowInstanceId` only → `startWithId(name, instanceId)`
  *   - `instanceId` + `input` → `startWithId(name, instanceId, input)`
  */
@scala.caps.assumeSafe
object Workflow:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the [[dapr4s.WorkflowName]] it starts — `def orderSaga` starts the workflow
    * `"orderSaga"` — overridable per method with [[name `@name`]]. The workflow runtime is fixed by the per-call
    * [[dapr4s.WorkflowCapability]], so `derive` takes no argument.
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Workflow"
    val capTpe = TypeRepr.of[WorkflowCapability]
    val instIdTpe = TypeRepr.of[WorkflowInstanceId]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      if !(resTpe =:= instIdTpe) then fail("a workflow start method must return WorkflowInstanceId.")

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a WorkflowCapability."))
        .asExprOf[WorkflowCapability]

      val instanceRef = values.collectFirst {
        case (n, r, t, _) if n == "instanceId" =>
          if !(t =:= instIdTpe) then fail("parameter `instanceId` must have type WorkflowInstanceId.")
          r
      }
      val inputEntry = values.find(v => v._1 != "instanceId")
      values.foreach { case (n, _, _, _) =>
        if n != "instanceId" && !inputEntry.exists(_._1 == n) then fail(s"unexpected parameter `$n`.")
      }

      val nm = MacroSupport.wireName(origSym)
      val nameExpr = '{ WorkflowName(${ Expr(nm) }) }

      def codecFor(arg: TypeRepr): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec for the input (JsonCodec[${arg.show}])."))

      (instanceRef, inputEntry) match
        case (None, None) =>
          '{ Forwarders.wfStart(${ capExpr }, ${ nameExpr }) }.asTerm
        case (Some(instRef), None) =>
          '{ Forwarders.wfStartWithId(${ capExpr }, ${ nameExpr }, ${ instRef.asExprOf[WorkflowInstanceId] }) }.asTerm
        case (None, Some((_, inputRef, inTpe, _))) =>
          inTpe.asType match
            case '[i] =>
              val codec = codecFor(inTpe).asExprOf[JsonCodec[i]]
              '{ Forwarders.wfStartInput[i](${ capExpr }, ${ nameExpr }, ${ inputRef.asExprOf[i] }, ${ codec }) }.asTerm
        case (Some(instRef), Some((_, inputRef, inTpe, _))) =>
          inTpe.asType match
            case '[i] =>
              val codec = codecFor(inTpe).asExprOf[JsonCodec[i]]
              '{
                Forwarders.wfStartWithIdInput[i](
                  ${ capExpr },
                  ${ nameExpr },
                  ${ instRef.asExprOf[WorkflowInstanceId] },
                  ${ inputRef.asExprOf[i] },
                  ${ codec },
                )
              }.asTerm
    }
