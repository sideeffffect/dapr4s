package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.ConfigurationCapability]] integration suite — a SINGLE cross-platform file, run via the per-platform
  * [[SharedDaprItSuite]] bring-up against the canonical `configuration.redis` store. Items are seeded into redis as
  * `value||version` by both harnesses (see [[ItNames.SeededConfig]]). Configuration is gRPC-only in the JS SDK, so on
  * Scala.js this also exercises the lazily created gRPC alpha1 client end to end.
  */
@scala.caps.assumeSafe
class ConfigurationItTest extends FunSuite, SharedDaprItSuite:

  test("configuration: get returns the seeded items with values and versions")(withDapr(getReturnsSeededItems))
  test("configuration: get for an unknown key returns no item for it")(withDapr(getUnknownKeyReturnsNoItem))

  def getReturnsSeededItems(using DaprCapability): Unit =
    DaprCapability.configuration(ItNames.ConfigStore):
      val items = ConfigurationCapability.get(Seq(ItNames.ConfigKeyA, ItNames.ConfigKeyB))
      val a = items.getOrElse(ItNames.ConfigKeyA, fail(s"missing ${ItNames.ConfigKeyA} in $items"))
      val b = items.getOrElse(ItNames.ConfigKeyB, fail(s"missing ${ItNames.ConfigKeyB} in $items"))
      assertEquals(a.value, ConfigurationValue("alpha"))
      assertEquals(a.version, ConfigurationVersion("v1"))
      assertEquals(b.value, ConfigurationValue("beta"))
      assertEquals(b.version, ConfigurationVersion("v2"))

  def getUnknownKeyReturnsNoItem(using DaprCapability): Unit =
    DaprCapability.configuration(ItNames.ConfigStore):
      val absent = ConfigurationKey(ItNames.fresh("dapr4s-it-absent"))
      assertEquals(ConfigurationCapability.get(Seq(absent)).get(absent), None)
