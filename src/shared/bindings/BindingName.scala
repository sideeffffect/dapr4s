package dapr4s.bindings

import dapr4s.*

import language.experimental.safe

/** Name of a Dapr input or output binding component.
  *
  * Must not be empty. Must match the `name` field in the binding component's metadata YAML so that the sidecar can
  * locate the correct binding at runtime.
  */
opaque type BindingName = String
object BindingName:
  def apply(s: String): BindingName =
    require(s.nonEmpty, "BindingName must not be empty")
    s
  extension (n: BindingName) def value: String = n
