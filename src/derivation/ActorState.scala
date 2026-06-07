package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.ActorContext]] per-instance state facades from a trait, using the getter/setter convention.
  *
  * Mirrors [[State]] but backed by the per-invocation `ActorContext`; the Scala member name maps verbatim (override
  * with [[name `@name`]]) to an [[dapr4s.ActorStateKey]]:
  *   - a getter `def x(using ActorContext, JsonCodec[T]): Option[T]` → `ctx.get(ActorStateKey("x"))`
  *   - a setter `def x_=(value: T)(using ActorContext, JsonCodec[T]): Unit` → `ctx.set(ActorStateKey("x"), value)`
  */
object ActorState:

  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "ActorState"
    val capTpe = TypeRepr.of[ActorContext]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      val ctxExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide an ActorContext."))
        .asExprOf[ActorContext]

      def codecFor(arg: TypeRepr): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[${arg.show}]."))

      val rawName = origSym.name
      val isSetter = rawName.endsWith("_=")
      val key = MacroSupport.nameOverride(origSym).getOrElse(if isSetter then rawName.stripSuffix("_=") else rawName)
      val keyExpr = '{ ActorStateKey(${ Expr(key) }) }

      if isSetter then
        val (_, valueRef, valueTpe, _) =
          values.headOption.getOrElse(fail("an actor-state setter needs a value parameter."))
        valueTpe.asType match
          case '[t] =>
            val codec = codecFor(valueTpe).asExprOf[JsonCodec[t]]
            '{ Forwarders.ctxSet[t](${ ctxExpr }, ${ keyExpr }, ${ valueRef.asExprOf[t] }, ${ codec }) }.asTerm
      else
        if values.nonEmpty then fail("an actor-state getter takes no value parameters.")
        val elemTpe = MacroSupport.optionArg(resTpe).getOrElse(fail("an actor-state getter must return Option[T]."))
        elemTpe.asType match
          case '[t] =>
            val codec = codecFor(elemTpe).asExprOf[JsonCodec[t]]
            '{ Forwarders.ctxGet[t](${ ctxExpr }, ${ keyExpr }, ${ codec }) }.asTerm
    }
