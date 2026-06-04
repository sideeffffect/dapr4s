package dapr4s.test.integration.apps

import scala.concurrent.duration.*

// DurationInt (the implicit behind 1.minute etc.) is not @assumedSafe, so duration
// literals can't be written inline in safe-mode files.  This non-safe file exposes
// pre-built FiniteDuration values that safe-mode code can reference.
@scala.caps.assumeSafe
private[apps] object Dur:
  val OneMinute: FiniteDuration = 1.minute
  val OneSecond: FiniteDuration = 1.second
  val TenSeconds: FiniteDuration = 10.seconds
