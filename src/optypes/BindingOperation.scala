package dapr.safe

import language.experimental.safe

opaque type BindingOperation = String
object BindingOperation:
  def apply(s: String): BindingOperation =
    require(s.nonEmpty, "BindingOperation must not be empty")
    s
  extension (n: BindingOperation) def value: String = n
