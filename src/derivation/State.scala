package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.StateCapability]] key/value facades from a trait, using the getter/setter convention.
  *
  * The state store is fixed by the per-call `StateCapability`; the Scala member name maps verbatim (override with
  * [[name `@name`]]) to a [[dapr4s.StateStoreKey]]:
  *   - a getter `def x(using StateCapability, JsonCodec[T]): Option[T]` → `get(StateStoreKey("x"))`
  *   - a setter `def x_=(value: T)(using StateCapability, JsonCodec[T]): Unit` → `save(StateStoreKey("x"), value)`
  *
  * {{{
  *   trait CounterState:
  *     def count(using StateCapability, JsonCodec[Int]): Option[Int]
  *     def count_=(value: Int)(using StateCapability, JsonCodec[Int]): Unit
  *   object CounterState extends State.Derived[CounterState]
  *
  *   val s = CounterState.derive
  *   s.count = 5
  *   val now = s.count   // Option[Int]
  * }}}
  */
object State:

  inline def derive[T]: T = ${ deriveImpl[T] }

  trait Derived[T]:
    inline def derive: T = State.derive[T]

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "State"
    val capTpe = TypeRepr.of[StateCapability]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a StateCapability."))
        .asExprOf[StateCapability]

      def codecFor(arg: TypeRepr): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[${arg.show}]."))

      val rawName = origSym.name
      val isSetter = rawName.endsWith("_=")
      val key = MacroSupport.nameOverride(origSym).getOrElse(if isSetter then rawName.stripSuffix("_=") else rawName)
      val keyExpr = '{ StateStoreKey(${ Expr(key) }) }

      if isSetter then
        val (_, valueRef, valueTpe, _) = values.headOption.getOrElse(fail("a state setter needs a value parameter."))
        valueTpe.asType match
          case '[t] =>
            val codec = codecFor(valueTpe).asExprOf[JsonCodec[t]]
            '{ Forwarders.stateSave[t](${ capExpr }, ${ keyExpr }, ${ valueRef.asExprOf[t] }, ${ codec }) }.asTerm
      else
        if values.nonEmpty then fail("a state getter takes no value parameters.")
        val elemTpe = MacroSupport.optionArg(resTpe).getOrElse(fail("a state getter must return Option[T]."))
        elemTpe.asType match
          case '[t] =>
            val codec = codecFor(elemTpe).asExprOf[JsonCodec[t]]
            '{ Forwarders.stateGet[t](${ capExpr }, ${ keyExpr }, ${ codec }) }.asTerm
    }
