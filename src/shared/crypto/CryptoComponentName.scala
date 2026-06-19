package dapr4s.crypto

import dapr4s.*

import language.experimental.safe

/** Name of a Dapr cryptography component.
  *
  * Must not be empty. Must match the `name` field in the crypto component's metadata YAML. Used when constructing a
  * [[CryptoCapability]] via [[DaprCapability.crypto]].
  */
opaque type CryptoComponentName = String
object CryptoComponentName:
  def apply(s: String): CryptoComponentName =
    require(s.nonEmpty, "CryptoComponentName must not be empty")
    s
  extension (n: CryptoComponentName) def value: String = n
