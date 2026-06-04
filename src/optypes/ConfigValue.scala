package dapr4s

/** The value of a configuration item.
  *
  * Returned as part of [[ConfigItem]] from [[ConfigurationCapability.get]] and configuration subscriptions. Wrapping
  * the value in a distinct type prevents accidental confusion with other string-typed values at call sites.
  *
  * @see
  *   [[ConfigItem]], [[ConfigurationCapability.get]]
  */
opaque type ConfigValue = String

object ConfigValue:
  def apply(value: String): ConfigValue = value
  extension (cv: ConfigValue) def value: String = cv
