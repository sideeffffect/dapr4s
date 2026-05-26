package dapr4s

import language.experimental.safe

/** Key identifying a configuration item in a Dapr configuration store.
  *
  * May be empty (the validity constraints depend on the backing configuration store). Pass one or more keys to
  * [[ConfigurationCapability.get]] or [[ConfigurationCapability.subscribe]] to retrieve or watch the corresponding
  * items.
  */
opaque type ConfigKey = String
object ConfigKey:
  def apply(s: String): ConfigKey = s
  extension (k: ConfigKey) def value: String = k
