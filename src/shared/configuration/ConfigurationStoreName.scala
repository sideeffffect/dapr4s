package dapr4s.configuration

import dapr4s.*

import language.experimental.safe

/** Name of a Dapr configuration store component.
  *
  * Must not be empty. Must match the `name` field in the configuration store component's metadata YAML. Used when
  * constructing a [[ConfigurationCapability]] via [[DaprCapability.configuration]].
  */
opaque type ConfigurationStoreName = String
object ConfigurationStoreName:
  def apply(s: String): ConfigurationStoreName =
    require(s.nonEmpty, "ConfigurationStoreName must not be empty")
    s
  extension (n: ConfigurationStoreName) def value: String = n
