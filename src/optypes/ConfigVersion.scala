package dapr.safe

/** A store-assigned version token for a [[ConfigItem]].
  *
  * Analogous to [[ETag]] for state entries: an opaque token returned by the configuration store to identify the version
  * of a configuration value. Not all stores populate this field; an empty token means the store does not support
  * versioning.
  *
  * @see
  *   [[ConfigItem.version]]
  */
opaque type ConfigVersion = String

@scala.caps.assumeSafe
object ConfigVersion:
  def apply(value: String): ConfigVersion = value
  extension (cv: ConfigVersion) def value: String = cv
