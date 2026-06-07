package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.BindingsCapability]] client facades from a trait.
  *
  * The Scala method name maps verbatim (override with [[name `@name`]]) to a [[dapr4s.BindingOperation]]. The binding
  * component itself is fixed by the per-call `BindingsCapability` (obtained via `DaprCapability.binding(name)`), so
  * `derive` takes no argument.
  *
  * Each method takes the request body as its first value parameter, plus an optional
  * `metadata: Map[MetadataKey, MetadataValue]` knob. The return type selects the overload:
  *   - `Option[Resp]` → [[dapr4s.BindingsCapability.invoke]] (response expected)
  *   - `Unit` → [[dapr4s.BindingsCapability.invokeOneWay]] (fire-and-forget)
  *
  * {{{
  *   trait EmailBinding:
  *     def create(req: EmailRequest)(using BindingsCapability, JsonCodec[EmailRequest]): Unit
  *   lazy val EmailBinding: EmailBinding = Bindings.derive[EmailBinding]
  * }}}
  */
@scala.caps.assumeSafe
object Bindings:

  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Bindings"
    val metadataTpe = TypeRepr.of[Map[MetadataKey, MetadataValue]]
    val capTpe = TypeRepr.of[BindingsCapability]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      val metaRef = values.collectFirst {
        case (n, r, t, _) if n == "metadata" =>
          if !(t =:= metadataTpe) then fail("parameter `metadata` must have type Map[MetadataKey, MetadataValue].")
          r
      }
      val bodyEntry = values.headOption.filterNot(_._1 == "metadata")
      values.foreach { case (n, _, _, _) =>
        if n != "metadata" && !bodyEntry.exists(_._1 == n) then
          fail(s"unexpected parameter `$n`; only the request body and `metadata` are allowed.")
      }
      val (_, bodyRef, reqTpe, _) = bodyEntry.getOrElse(fail("a binding method needs a request-body parameter."))

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a BindingsCapability."))
        .asExprOf[BindingsCapability]

      def codecFor(arg: TypeRepr, role: String): Term =
        givens
          .collectFirst { case (_, r, t, _) if MacroSupport.jsonCodecArg(t).exists(_ =:= arg) => r }
          .getOrElse(fail(s"the `using` clause must provide a JsonCodec[$role] (JsonCodec[${arg.show}])."))

      val nm = MacroSupport.wireName(origSym)
      val metaExpr =
        metaRef.map(_.asExprOf[Map[MetadataKey, MetadataValue]]).getOrElse('{ Map.empty[MetadataKey, MetadataValue] })

      reqTpe.asType match
        case '[req] =>
          val reqCodec = codecFor(reqTpe, "Req").asExprOf[JsonCodec[req]]
          if MacroSupport.isUnit(resTpe) then
            '{
              Forwarders.bindingInvokeOneWay[req](
                ${ capExpr },
                BindingOperation(${ Expr(nm) }),
                ${ bodyRef.asExprOf[req] },
                ${ metaExpr },
                ${ reqCodec },
              )
            }.asTerm
          else
            val respArg = MacroSupport
              .optionArg(resTpe)
              .getOrElse(fail("a value-returning binding method must return Option[Resp] (or Unit for one-way)."))
            respArg.asType match
              case '[resp] =>
                val respCodec = codecFor(respArg, "Resp").asExprOf[JsonCodec[resp]]
                '{
                  Forwarders.bindingInvoke[req, resp](
                    ${ capExpr },
                    BindingOperation(${ Expr(nm) }),
                    ${ bodyRef.asExprOf[req] },
                    ${ metaExpr },
                    ${ reqCodec },
                    ${ respCodec },
                  )
                }.asTerm
    }
