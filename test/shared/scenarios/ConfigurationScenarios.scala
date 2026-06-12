package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.Assertions
import unsafeExceptions.canThrowAny

/** Direct-call [[ConfigurationCapability]] scenarios shared by the JVM and JS integration suites, against the canonical
  * `configuration.redis` store. Items are seeded into redis as `value||version` by both harnesses (see
  * [[ItNames.ConfigKeyA]]). Configuration is gRPC-only in the JS SDK, so the JS twin exercises the gRPC alpha1 client.
  */
trait ConfigurationScenarios:
  self: Assertions =>

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
