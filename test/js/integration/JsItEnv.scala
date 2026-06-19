//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given

/** Scala.js integration-suite constants + the JS implementation of the cross-platform [[ItPolling]] helpers (the actual
  * `sleep` lives in [[JsItPolling]]).
  *
  * Bring-up lives in [[DaprJsIt]] / [[SharedDaprItSuite]] / [[ServerDaprItSuite]], which drive a real Dapr sidecar from
  * inside the test runtime via `@dapr/testcontainer-node` — the twin of the JVM testcontainers fixtures.
  *
  * WHY @assumeSafe: see [[ItPolling]] — the by-name probe/body closures capture Dapr capabilities from the enclosing
  * `Dapr.run` scope.
  */
@scala.caps.assumeSafe
object JsItEnv extends JsItPolling:

  // Canonical component names, config/secret keys, the server app id and the `fresh` id generator live
  // once in the cross-platform ItNames; re-export them so the JS suites keep a single `import JsItEnv.*`.
  export ItNames.*
