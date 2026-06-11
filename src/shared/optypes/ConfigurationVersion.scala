package dapr4s

/** A store-assigned version token for a [[ConfigurationItem]].
  *
  * Analogous to [[ETag]] for state entries: an opaque token returned by the configuration store to identify the version
  * of a configuration value. Not all stores populate this field; an empty token means the store does not support
  * versioning.
  *
  * @see
  *   [[ConfigurationItem.version]]
  */
opaque type ConfigurationVersion = String

@scala.caps.assumeSafe
object ConfigurationVersion:
  def apply(value: String): ConfigurationVersion = value
  extension (cv: ConfigurationVersion) def value: String = cv
