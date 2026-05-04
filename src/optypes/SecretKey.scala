package dapr.safe

import language.experimental.safe

opaque type SecretKey = String
object SecretKey:
  def apply(s: String): SecretKey = s
  extension (k: SecretKey) def value: String = k
