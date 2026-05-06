package dapr.safe

/** Typed key-value metadata map forwarded to Dapr components.
  *
  * Use [[Metadata.empty]] when no metadata is needed, or [[Metadata.apply]] to construct from pairs:
  * {{{
  *   Metadata("content-type" -> "application/json", "dapr-app-id" -> "my-service")
  * }}}
  *
  * @see
  *   [[StateCapability.saveWithETag]], [[SecretsCapability.get]], [[ConfigurationCapability.get]],
  *   [[BindingsCapability.invoke]], [[ServiceInvocationCapability.invoke]]
  */
opaque type Metadata = Map[String, String]

@scala.caps.assumeSafe
object Metadata:
  /** An empty metadata map (no metadata forwarded). */
  val empty: Metadata = Map.empty

  /** Construct metadata from a list of key-value pairs. */
  def apply(pairs: (String, String)*): Metadata = pairs.toMap

  /** Wrap an existing `Map[String, String]` as [[Metadata]]. */
  def from(map: Map[String, String]): Metadata = map

  extension (m: Metadata)
    /** Returns the underlying `Map[String, String]`. */
    def toMap: Map[String, String] = m
