//> using target.platform "jvm"
package dapr4s.test.integration

import munit.FunSuite

/** JVM [[dapr4s.CryptoCapability]] integration suite: a one-line entry point over the shared [[CryptoSuiteDef]]
  * (registrations + scenarios) and [[SharedDaprItSuite]] (the canonical `crypto.dapr.localstorage` store backed by a
  * fresh RSA key). The JS twin [[CryptoJsIntegrationTest]] runs the very same suite definition.
  */
@scala.caps.assumeSafe
class CryptoItTest extends FunSuite, SharedDaprItSuite, CryptoSuiteDef
