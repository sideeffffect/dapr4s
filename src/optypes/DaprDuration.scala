package dapr.safe

import language.experimental.safe

/** Go-style duration string used by the Dapr actor runtime (e.g. "1h", "30s", "500ms").
  *
  * Dapr accepts durations in Go format: one or more `<number><unit>` pairs where unit is one of
  * `ns`, `us`, `µs`, `ms`, `s`, `m`, `h`.
  */
opaque type DaprDuration = String
object DaprDuration:
  private val pattern = raw"(\d+(\.\d+)?(ns|us|µs|ms|s|m|h))+".r
  def apply(s: String): DaprDuration =
    require(
      pattern.matches(s),
      s"Invalid Dapr duration: '$s' (expected Go duration like '1h', '30s', '500ms', '1h30m')",
    )
    s
  extension (d: DaprDuration) def value: String = d
