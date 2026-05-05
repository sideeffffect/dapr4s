package dapr.safe

import language.experimental.safe

/** Name of a Dapr configuration store component.
  *
  * Must not be empty. Must match the `name` field in the configuration store component's metadata YAML. Used when
  * constructing a [[ConfigurationCapability]] via [[DaprCapability.configuration]].
  */
opaque type ConfigStoreName = String
object ConfigStoreName:
  def apply(s: String): ConfigStoreName =
    require(s.nonEmpty, "ConfigStoreName must not be empty")
    s
  extension (n: ConfigStoreName) def value: String = n
