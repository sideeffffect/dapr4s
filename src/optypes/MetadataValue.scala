package dapr.safe

/** A typed metadata map value, used in [[Map[MetadataKey, MetadataValue]]] parameters across Dapr capability methods.
  */
opaque type MetadataValue = String

@scala.caps.assumeSafe
object MetadataValue:
  def apply(value: String): MetadataValue = value
  extension (mv: MetadataValue) def value: String = mv
