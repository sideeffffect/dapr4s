package dapr.safe

import language.experimental.safe

opaque type SecretStoreName = String
object SecretStoreName:
  def apply(s: String): SecretStoreName =
    require(s.nonEmpty, "SecretStoreName must not be empty")
    s
  extension (n: SecretStoreName) def value: String = n
