package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.ConfigurationCapability]] reader facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to a single [[dapr4s.ConfigKey]]; the config store is
  * fixed by the per-call `ConfigurationCapability`. Each method takes no request body, an optional
  * `metadata: Map[MetadataKey, MetadataValue]` knob, and returns `Option[ConfigItem]` (the entry for that one key).
  *
  * {{{
  *   trait Config:
  *     @name("feature-x") def featureX()(using ConfigurationCapability): Option[ConfigItem]
  *   lazy val Config: Config = Configuration.derive[Config]
  * }}}
  */
@scala.caps.assumeSafe
object Configuration:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the single [[dapr4s.ConfigKey]] it reads — `def featureX` reads the config entry
    * `"featureX"` — overridable per method with [[name `@name`]]. The config store itself is fixed by the per-call
    * [[dapr4s.ConfigurationCapability]], so `derive` takes no argument.
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Configuration"
    val metadataTpe = TypeRepr.of[Map[MetadataKey, MetadataValue]]
    val capTpe = TypeRepr.of[ConfigurationCapability]
    val configItemOpt = TypeRepr.of[Option[ConfigItem]]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      if !(resTpe =:= configItemOpt) then fail("a config getter must return Option[ConfigItem].")
      val metaRef = values.collectFirst {
        case (n, r, t, _) if n == "metadata" =>
          if !(t =:= metadataTpe) then fail("parameter `metadata` must have type Map[MetadataKey, MetadataValue].")
          r
      }
      values.foreach { case (n, _, _, _) =>
        if n != "metadata" then fail(s"unexpected parameter `$n`; a config getter takes only an optional `metadata`.")
      }

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a ConfigurationCapability."))
        .asExprOf[ConfigurationCapability]

      val nm = MacroSupport.wireName(origSym)
      val metaExpr =
        metaRef.map(_.asExprOf[Map[MetadataKey, MetadataValue]]).getOrElse('{ Map.empty[MetadataKey, MetadataValue] })
      '{ Forwarders.configGet(${ capExpr }, ConfigKey(${ Expr(nm) }), ${ metaExpr }) }.asTerm
    }
