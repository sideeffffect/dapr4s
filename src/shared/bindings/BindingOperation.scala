package dapr4s.bindings

import dapr4s.*

import language.experimental.safe

/** The operation name passed to an output binding.
  *
  * Must not be empty. The set of valid operations depends on the binding type; common values include `"create"`,
  * `"post"`, and `"delete"`. Consult the Dapr binding component documentation for the exact operations supported by a
  * given component.
  */
opaque type BindingOperation = String
object BindingOperation:
  def apply(s: String): BindingOperation =
    require(s.nonEmpty, "BindingOperation must not be empty")
    s
  extension (n: BindingOperation) def value: String = n
