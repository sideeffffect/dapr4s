package dapr4s

/** A typed metadata map key, used in [[Map[MetadataKey, MetadataValue]]] parameters across Dapr capability methods. */
opaque type MetadataKey = String

@scala.caps.assumeSafe
object MetadataKey:
  def apply(key: String): MetadataKey = key
  extension (mk: MetadataKey) def value: String = mk
