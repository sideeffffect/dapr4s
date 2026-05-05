package dapr.safe

import language.experimental.safe

/** Name of a Dapr secrets store component.
  *
  * Must not be empty. Must match the `name` field in the secrets store component's metadata YAML. Used when
  * constructing a [[SecretsCapability]] via [[DaprCapability.secrets]].
  */
opaque type SecretStoreName = String
object SecretStoreName:
  def apply(s: String): SecretStoreName =
    require(s.nonEmpty, "SecretStoreName must not be empty")
    s
  extension (n: SecretStoreName) def value: String = n
