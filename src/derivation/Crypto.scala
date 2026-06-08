package dapr4s.derivation

import dapr4s.*
import scala.collection.immutable.ArraySeq
import scala.quoted.*

/** Derivation of [[dapr4s.CryptoCapability]] encrypt facades from a trait.
  *
  * The method name maps verbatim (override with [[name `@name`]]) to a [[dapr4s.CryptoKeyName]]; the crypto component
  * is fixed by the per-call `CryptoCapability`. Each method takes the plaintext as its first value parameter and a
  * required `algorithm: KeyWrapAlgorithm`, and returns `ArraySeq[Byte]`. The plaintext type selects the overload:
  *   - `ArraySeq[Byte]` → [[dapr4s.CryptoCapability.encrypt]]
  *   - `String` → [[dapr4s.CryptoCapability.encryptString]]
  */
@scala.caps.assumeSafe
object Crypto:

  /** Derive a client facade for trait `T`.
    *
    * Each method's Scala name is the [[dapr4s.CryptoKeyName]] it encrypts under — `def sessionKey` encrypts with the
    * key named `"sessionKey"` — overridable per method with [[name `@name`]]. The crypto component itself is fixed by
    * the per-call [[dapr4s.CryptoCapability]], so `derive` takes no argument.
    *
    * {{{
    *   trait Vault:
    *     def sessionKey(plaintext: ArraySeq[Byte], algorithm: KeyWrapAlgorithm)(using CryptoCapability): ArraySeq[Byte]
    *     @name("text-key") def textKey(plaintext: String, algorithm: KeyWrapAlgorithm)(using CryptoCapability): ArraySeq[Byte]
    *   lazy val Vault: Vault = Crypto.derive[Vault]
    *
    *   DaprCapability.crypto(CryptoComponentName("vault")) {
    *     Vault.sessionKey(bytes, KeyWrapAlgorithm("RSA"))  // encrypts under key "sessionKey"
    *     Vault.textKey("hello", KeyWrapAlgorithm("RSA"))   // encrypts under key "text-key"
    *   }
    * }}}
    */
  inline def derive[T]: T = ${ deriveImpl[T] }

  private def deriveImpl[T: Type](using Quotes): Expr[T] =
    import quotes.reflect.*
    val engine = "Crypto"
    val capTpe = TypeRepr.of[CryptoCapability]
    val algoTpe = TypeRepr.of[KeyWrapAlgorithm]
    val bytesTpe = TypeRepr.of[ArraySeq[Byte]]
    val stringTpe = TypeRepr.of[String]

    MacroSupport.deriveTrait[T](engine) { (origSym, newSym, argss) =>
      val info = MacroSupport.paramInfo(origSym, newSym, argss)
      val resTpe = MacroSupport.resultTypeOf(origSym)
      val givens = info.filter(_._4)
      val values = info.filterNot(_._4)
      def fail(m: String): Nothing = MacroSupport.fail(engine, origSym, m)

      if !(resTpe =:= bytesTpe) then fail("an encrypt method must return ArraySeq[Byte].")
      val algoRef = values
        .collectFirst {
          case (n, r, t, _) if n == "algorithm" =>
            if !(t =:= algoTpe) then fail("parameter `algorithm` must have type KeyWrapAlgorithm.")
            r
        }
        .getOrElse(fail("an encrypt method needs an `algorithm: KeyWrapAlgorithm` parameter."))
      val bodyEntry = values.headOption.filterNot(_._1 == "algorithm")
      values.foreach { case (n, _, _, _) =>
        if n != "algorithm" && !bodyEntry.exists(_._1 == n) then
          fail(s"unexpected parameter `$n`; only the plaintext and `algorithm` are allowed.")
      }
      val (_, bodyRef, plainTpe, _) = bodyEntry.getOrElse(fail("an encrypt method needs a plaintext parameter."))

      val capExpr = givens
        .collectFirst { case (_, r, t, _) if t <:< capTpe => r }
        .getOrElse(fail("the `using` clause must provide a CryptoCapability."))
        .asExprOf[CryptoCapability]

      val nm = MacroSupport.wireName(origSym)
      val keyExpr = '{ CryptoKeyName(${ Expr(nm) }) }
      val algoExpr = algoRef.asExprOf[KeyWrapAlgorithm]

      if plainTpe =:= bytesTpe then
        '{
          Forwarders.cryptoEncrypt(${ capExpr }, ${ keyExpr }, ${ bodyRef.asExprOf[ArraySeq[Byte]] }, ${ algoExpr })
        }.asTerm
      else if plainTpe =:= stringTpe then
        '{
          Forwarders.cryptoEncryptString(${ capExpr }, ${ keyExpr }, ${ bodyRef.asExprOf[String] }, ${ algoExpr })
        }.asTerm
      else fail("the plaintext parameter must be ArraySeq[Byte] (encrypt) or String (encryptString).")
    }
