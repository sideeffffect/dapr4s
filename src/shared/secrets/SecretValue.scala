package dapr4s.secrets

import dapr4s.*

/** The plaintext value of a Dapr secret.
  *
  * Returned by [[SecretsCapability.get]] and [[SecretsCapability.getBulk]]. Wrapping the value in a distinct type
  * prevents accidental confusion with other string-typed values and makes leakage (e.g. into logs) more visible at call
  * sites.
  *
  * @see
  *   [[SecretsCapability.get]], [[SecretsCapability.getBulk]]
  */
opaque type SecretValue = String

object SecretValue:
  def apply(value: String): SecretValue = value
  extension (sv: SecretValue) def value: String = sv
