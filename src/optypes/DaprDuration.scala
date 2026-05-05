package dapr.safe

import scala.concurrent.duration.{FiniteDuration, Duration}

/** A non-negative duration used in Dapr actor runtime configuration.
  *
  * Backed by [[FiniteDuration]] for type-safe arithmetic and interoperability. Serialized to Go duration format (e.g.
  * `"1h"`, `"30s"`, `"500ms"`) when sent to the Dapr sidecar via `GET /dapr/config`.
  */
opaque type DaprDuration = FiniteDuration
object DaprDuration:
  def apply(d: FiniteDuration): DaprDuration =
    require(d.length >= 0, s"DaprDuration must be non-negative, got $d")
    d

  extension (d: DaprDuration)
    def value: FiniteDuration = d

    /** Serialize to Go duration format as expected by the Dapr sidecar `GET /dapr/config` response. */
    def toGoString: String =
      val nanos = d.toNanos
      if nanos == 0L then "0s"
      else
        val h = nanos / 3_600_000_000_000L
        val r1 = nanos % 3_600_000_000_000L
        val m = r1 / 60_000_000_000L
        val r2 = r1 % 60_000_000_000L
        val s = r2 / 1_000_000_000L
        val r3 = r2 % 1_000_000_000L
        val ms = r3 / 1_000_000L
        val us = r3 % 1_000_000L / 1_000L
        val ns = r3 % 1_000L
        List(h -> "h", m -> "m", s -> "s", ms -> "ms", us -> "us", ns -> "ns").collect {
          case (v, unit) if v > 0 => s"$v$unit"
        }.mkString
