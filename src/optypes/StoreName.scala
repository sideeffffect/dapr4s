package dapr.safe

import language.experimental.safe

opaque type StoreName = String
object StoreName:
  def apply(s: String): StoreName =
    require(s.nonEmpty, "StoreName must not be empty")
    s
  extension (n: StoreName) def value: String = n
