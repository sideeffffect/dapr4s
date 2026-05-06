package dapr.safe

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

@scala.caps.assumeSafe
object SecretValue:
  def apply(value: String): SecretValue = value
  extension (sv: SecretValue) def value: String = sv
  // Within this companion SecretValue = String (transparent), so JsonCodec[String] serves as JsonCodec[SecretValue].
  given JsonCodec[SecretValue] = summon[JsonCodec[String]]
