package dapr4s.derivation

import dapr4s.*
import scala.quoted.*

/** Derivation of [[dapr4s.SecretsCapability]] reader facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to a [[dapr4s.SecretKey]]; the secret store is fixed
  * by the per-call `SecretsCapability`. Each method takes no request body, an optional
  * `metadata: Map[MetadataKey, MetadataValue]` knob, and returns `Option[SecretValue]`.
  *
  * {{{
  *   trait Secrets:
  *     @name("db-password") def dbPassword()(using SecretsCapability): Option[SecretValue]
  *   lazy val Secrets: Secrets = Secrets.derive[Secrets]
  * }}}
  */
@scala.caps.assumeSafe
object Secrets:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the [[dapr4s.SecretKey]] it reads — `@name("db-password") def dbPassword` reads the
    * secret `"db-password"` — overridable per method with [[name `@name`]]. The secret store itself is fixed by the
    * per-call [[dapr4s.SecretsCapability]], so `derive` takes no argument.
    *
    * {{{
    *   trait Secrets:
    *     @name("db-password") def dbPassword()(using SecretsCapability): Option[SecretValue]
    *   lazy val Secrets: Secrets = Secrets.derive[Secrets]
    *
    *   DaprCapability.secrets(SecretStoreName("vault")) {
    *     Secrets.dbPassword() // reads SecretKey("db-password")
    *   }
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Secrets"
    val metadataTpe = TypeRepr.of[Map[MetadataKey, MetadataValue]]
    val capTpe = TypeRepr.of[SecretsCapability]
    val secretValueOpt = TypeRepr.of[Option[SecretValue]]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      if !(resTpe =:= secretValueOpt) then fail("a secret getter must return Option[SecretValue].")
      val metaRef = values.collectFirst {
        case (n, r, t, _) if n == "metadata" =>
          if !(t =:= metadataTpe) then fail("parameter `metadata` must have type Map[MetadataKey, MetadataValue].")
          r
      }
      values.foreach { case (n, _, _, _) =>
        if n != "metadata" then fail(s"unexpected parameter `$n`; a secret getter takes only an optional `metadata`.")
      }

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a SecretsCapability."))
        .asExprOf[SecretsCapability]

      val nm = MacroSupport.wireName(origSym)
      val metaExpr =
        metaRef.map(_.asExprOf[Map[MetadataKey, MetadataValue]]).getOrElse('{ Map.empty[MetadataKey, MetadataValue] })
      '{ Forwarders.secretsGet(${ capExpr }, SecretKey(${ Expr(nm) }), ${ metaExpr }) }.asTerm
    }
