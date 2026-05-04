package dapr.safe

import language.experimental.safe

opaque type MethodName = String
object MethodName:
  def apply(s: String): MethodName =
    require(s.nonEmpty, "MethodName must not be empty")
    s
  extension (n: MethodName) def value: String = n
