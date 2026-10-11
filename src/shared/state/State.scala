package dapr4s.state

import dapr4s.derivation.*

import dapr4s.*
import dapr4s.state.*
import scala.quoted.*

/** Derivation of [[StateCapability.Instance]] key/value facades from a trait, using the getter/setter convention.
  *
  * The state store is fixed by the per-call `StateCapability.Instance`; the Scala member name maps verbatim (override
  * with [[name `@name`]]) to a [[dapr4s.StateStoreKey]]:
  *   - a getter `def x(using StateCapability.Instance.ReadOnly, JsonCodec[T]): Option[T]` (a full `Instance` also
  *     works) → `get(StateStoreKey("x"))`
  *   - a setter `def x_=(value: T)(using StateCapability.Instance, JsonCodec[T]): Unit` →
  *     `save(StateStoreKey("x"), value)`
  *
  * {{{
  *   trait CounterState:
  *     def count(using StateCapability.Instance, JsonCodec[Int]): Option[Int]
  *     def count_=(value: Int)(using StateCapability.Instance, JsonCodec[Int]): Unit
  *   lazy val CounterState: CounterState = State.derive[CounterState]
  *
  *   val s = CounterState
  *   s.count = 5
  *   val now = s.count   // Option[Int]
  * }}}
  */
@scala.caps.assumeSafe
object State:

  /** Derive a client facade for trait `T`.
    *
    * Each member's Scala name is the [[dapr4s.StateStoreKey]] it reads or writes — getter `def count` and setter
    * `def count_=` both address the key `"count"` (the `_=` setter suffix is dropped when forming the key) —
    * overridable with [[name `@name`]]. The state store itself is fixed by the per-call [[StateCapability.Instance]],
    * so `derive` takes no argument.
    *
    * {{{
    *   trait Counter:
    *     def count(using StateCapability.Instance, JsonCodec[Int]): Option[Int]
    *     def count_=(value: Int)(using StateCapability.Instance, JsonCodec[Int]): Unit
    *   lazy val Counter: Counter = State.derive[Counter]
    *
    *   DaprCapability.state(StateStoreName("statestore")) {
    *     Counter.count = 5            // → save(StateStoreKey("count"), 5)
    *     val now = Counter.count      // → get(StateStoreKey("count")): Option[Int]
    *   }
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "State"
    // Getters need only read authority; setters need the full store-bound capability.
    val readTpe = TypeRepr.of[StateCapability.Instance.ReadOnly]
    val writeTpe = TypeRepr.of[StateCapability.Instance]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      def capFor(tpe: TypeRepr, what: String): Term =
        givens
          .collectFirst { case (_, r, t, _) if t <:< tpe => r }
          .getOrElse(fail(s"the `using` clause must provide a $what."))

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
            '{
              Forwarders.stateSave[t](
                ${ capFor(writeTpe, "StateCapability.Instance").asExprOf[StateCapability.Instance] },
                ${ keyExpr },
                ${ valueRef.asExprOf[t] },
                ${ codec },
              )
            }.asTerm
      else
        if values.nonEmpty then fail("a state getter takes no value parameters.")
        val elemTpe = MacroSupport.optionArg(resTpe).getOrElse(fail("a state getter must return Option[T]."))
        elemTpe.asType match
          case '[t] =>
            val codec = codecFor(elemTpe).asExprOf[JsonCodec[t]]
            '{
              Forwarders.stateGet[t](
                ${ capFor(readTpe, "StateCapability.Instance.ReadOnly").asExprOf[StateCapability.Instance.ReadOnly] },
                ${ keyExpr },
                ${ codec },
              )
            }.asTerm
    }
