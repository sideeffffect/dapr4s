package dapr4s

import language.experimental.safe

/** Key identifying a configuration item in a Dapr configuration store.
  *
  * May be empty (the validity constraints depend on the backing configuration store). Pass one or more keys to
  * [[ConfigurationCapability.get]] or [[ConfigurationCapability.subscribe]] to retrieve or watch the corresponding
  * items.
  */
opaque type ConfigurationKey = String
object ConfigurationKey:
  def apply(s: String): ConfigurationKey = s
  extension (k: ConfigurationKey) def value: String = k
