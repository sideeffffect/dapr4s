package dapr4s.secrets

import dapr4s.*

import language.experimental.safe

/** Key used to look up a secret in a Dapr secrets store.
  *
  * May be empty (the validity constraints depend on the backing secrets store implementation). Format and hierarchy
  * conventions (e.g. slash-separated paths for Vault) are store-specific. Pass to [[SecretsCapability.get]] to retrieve
  * the corresponding secret value.
  */
opaque type SecretKey = String
object SecretKey:
  def apply(s: String): SecretKey = s
  extension (k: SecretKey) def value: String = k
