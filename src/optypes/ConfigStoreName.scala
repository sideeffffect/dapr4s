package dapr.safe

import language.experimental.safe

opaque type ConfigStoreName = String
object ConfigStoreName:
  def apply(s: String): ConfigStoreName =
    require(s.nonEmpty, "ConfigStoreName must not be empty")
    s
  extension (n: ConfigStoreName) def value: String = n
