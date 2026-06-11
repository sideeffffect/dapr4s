package dapr4s

/** The value of a configuration item.
  *
  * Returned as part of [[ConfigurationItem]] from [[ConfigurationCapability.get]] and configuration subscriptions.
  * Wrapping the value in a distinct type prevents accidental confusion with other string-typed values at call sites.
  *
  * @see
  *   [[ConfigurationItem]], [[ConfigurationCapability.get]]
  */
opaque type ConfigurationValue = String

object ConfigurationValue:
  def apply(value: String): ConfigurationValue = value
  extension (cv: ConfigurationValue) def value: String = cv
