package dapr4s.test.integration.apps

import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*

import scala.concurrent.duration.*

// DurationInt (the implicit behind 1.minute etc.) is not @assumedSafe, so duration
// literals can't be written inline in safe-mode files.  This non-safe file exposes
// pre-built FiniteDuration values that safe-mode code can reference.
@scala.caps.assumeSafe
private[apps] object Dur:
  val OneMinute: FiniteDuration = 1.minute
  val OneSecond: FiniteDuration = 1.second
  val TenSeconds: FiniteDuration = 10.seconds
