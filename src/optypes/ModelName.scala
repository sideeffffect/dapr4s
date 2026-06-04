package dapr4s

/** The model identifier a provider reports for a completion (e.g. "gpt-4o").
  *
  * Read from [[ChatResult.model]].
  */
opaque type ModelName = String

object ModelName:
  def apply(value: String): ModelName = value
  extension (n: ModelName) def value: String = n
