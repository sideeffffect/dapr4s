package dapr4s.crypto

import dapr4s.*

import language.experimental.safe

/** Name of a key managed by a Dapr cryptography component.
  *
  * Must not be empty. Identifies which key the component uses to wrap the data encryption key during
  * [[CryptoCapability.encrypt]].
  */
opaque type CryptoKeyName = String
object CryptoKeyName:
  def apply(s: String): CryptoKeyName =
    require(s.nonEmpty, "CryptoKeyName must not be empty")
    s
  extension (n: CryptoKeyName) def value: String = n
