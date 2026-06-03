package dapr4s

import language.experimental.safe

/** Algorithm used to wrap the data encryption key with the named key during [[CryptoCapability.encrypt]].
  *
  * Must not be empty. The accepted values depend on the key type configured in the crypto component; the constants
  * below cover the common cases. Any other algorithm string supported by the component may be passed.
  */
opaque type KeyWrapAlgorithm = String
object KeyWrapAlgorithm:
  def apply(s: String): KeyWrapAlgorithm =
    require(s.nonEmpty, "KeyWrapAlgorithm must not be empty")
    s

  /** RSA key wrapping (for RSA keys). */
  val Rsa: KeyWrapAlgorithm = "RSA"

  /** AES key wrapping (for symmetric keys). */
  val Aes: KeyWrapAlgorithm = "AES"

  extension (a: KeyWrapAlgorithm) def value: String = a
