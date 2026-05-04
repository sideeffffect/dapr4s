package dapr.safe

import language.experimental.safe

opaque type ConfigKey = String
object ConfigKey:
  def apply(s: String): ConfigKey = s
  extension (k: ConfigKey) def value: String = k
