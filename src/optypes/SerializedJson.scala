package dapr4s

/** A pre-serialised JSON string, used for workflow input and output payloads.
  *
  * Obtain instances from [[WorkflowSnapshot.serializedInput]] and [[WorkflowSnapshot.serializedOutput]]. Use [[decode]]
  * or [[decodeOrThrow]] to recover the typed value:
  * {{{
  *   val result: CounterState = snapshot.serializedOutput
  *     .getOrElse(throw RuntimeException("no output"))
  *     .decodeOrThrow[CounterState]
  * }}}
  */
opaque type SerializedJson = String

@scala.caps.assumeSafe
object SerializedJson:
  def apply(json: String): SerializedJson = json
  extension (sj: SerializedJson)
    /** The raw JSON string. */
    def value: String = sj

    /** Attempt to decode the JSON into `T`. */
    def decode[T: JsonCodec]: Either[JsonDecodeException, T] = summon[JsonCodec[T]].decode(sj)

    /** Decode the JSON into `T`, throwing [[JsonDecodeException]] on failure. */
    def decodeOrThrow[T: JsonCodec]: T = JsonCodec.decodeOrThrow[T](sj)
