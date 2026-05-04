package dapr.safe

import language.experimental.safe

opaque type BindingName = String
object BindingName:
  def apply(s: String): BindingName =
    require(s.nonEmpty, "BindingName must not be empty")
    s
  extension (n: BindingName) def value: String = n
