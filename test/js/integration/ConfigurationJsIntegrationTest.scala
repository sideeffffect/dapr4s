//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** [[ConfigurationCapability]] against a real `configuration.redis` component. The keys are seeded by
  * `scripts/js-integration-env.sh up` via `docker exec ... redis-cli MSET` (Dapr's redis configuration store splits
  * `value||version` into value + version). Configuration is gRPC-only in the JS SDK, so this is also the suite that
  * exercises the lazily created gRPC-protocol client end to end.
  */
@scala.caps.assumeSafe
class ConfigurationJsIntegrationTest extends FunSuite:

  override def munitTimeout: Duration = 120.seconds

  test("configuration: get returns the seeded items with values and versions"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.configuration(ConfigStore) {
          val keyA = ConfigurationKey("dapr4s-js-it-cfg-a")
          val keyB = ConfigurationKey("dapr4s-js-it-cfg-b")
          val items = ConfigurationCapability.get(Seq(keyA, keyB))
          val a = items.getOrElse(keyA, fail(s"missing $keyA in $items"))
          val b = items.getOrElse(keyB, fail(s"missing $keyB in $items"))
          assertEquals(a.value, ConfigurationValue("alpha"))
          assertEquals(a.version, ConfigurationVersion("v1"))
          assertEquals(b.value, ConfigurationValue("beta"))
          assertEquals(b.version, ConfigurationVersion("v2"))
        }
    }.toFuture

  test("configuration: get for an unknown key returns no item for it"):
    js.async {
      Dapr(clientConfig).run:
        DaprCapability.configuration(ConfigStore) {
          val absent = ConfigurationKey(s"dapr4s-js-it-absent-${uniqueId()}")
          val items = ConfigurationCapability.get(Seq(absent))
          assertEquals(items.get(absent), None)
        }
    }.toFuture
