//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[SecretsCapability]] against a real `secretstores.local.file` component (seeded from the shared
  * `scripts/it/secrets.json`) — the Scala.js twin of [[SecretsCapabilityServerTest]].
  *
  * A missing key THROWS rather than returning `None`: the local-file store answers 500, which rejects the SDK promise —
  * the documented behaviour on both platforms (`SecretsCapabilityImpl`: "a sidecar error REJECTS the promise and
  * propagates; None is returned only when the call succeeds but the response lacks the key").
  */
@scala.caps.assumeSafe
class SecretsJsIntegrationTest extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  test("secrets: get for a seeded key returns Some"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.secrets(SecretStore) {
          assertEquals(SecretsCapability.get(SecretKey("it-secret-a")), Some(SecretValue("secret-value-alpha")))
          assertEquals(SecretsCapability.get(SecretKey("it-secret-b")), Some(SecretValue("secret-value-beta")))
        }
    }.toFuture

  test("secrets: getBulk contains the seeded keys"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.secrets(SecretStore) {
          val bulk = SecretsCapability.getBulk()
          // The bulk response nests {secretName: {key: value}}; dapr4s flattens to "name/key" compound keys.
          assert(
            bulk.exists { case (k, v) => k.value.contains("it-secret-a") && v.value == "secret-value-alpha" },
            s"expected it-secret-a in bulk result; got keys: ${bulk.keys.map(_.value).toList.sorted}",
          )
          assert(
            bulk.exists { case (k, v) => k.value.contains("it-secret-b") && v.value == "secret-value-beta" },
            "expected it-secret-b in bulk result",
          )
        }
    }.toFuture

  test("secrets: get for a missing key throws (local-file store answers 500)"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.secrets(SecretStore) {
          val attempt = scala.util.Try(SecretsCapability.get(SecretKey(s"absent-${uniqueId()}")))
          assert(attempt.isFailure, s"expected a missing secret to throw, got: $attempt")
        }
    }.toFuture
