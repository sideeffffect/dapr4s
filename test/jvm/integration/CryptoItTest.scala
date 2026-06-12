//> using target.platform "jvm"
package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** JVM [[dapr4s.CryptoCapability]] integration suite: a thin shell over the shared [[CryptoScenarios]] and
  * [[SharedDaprItSuite]] (the canonical `crypto.dapr.localstorage` store backed by a fresh RSA key). The JS twin
  * [[CryptoJsIntegrationTest]] runs the very same scenarios. Replaces the former CryptoCapabilityServerTest.
  */
@scala.caps.assumeSafe
class CryptoItTest extends FunSuite, SharedDaprItSuite, CryptoScenarios:

  test("crypto: encryptString then decryptString round-trips the original text")(
    withDapr(encryptDecryptStringRoundTrip),
  )
  test("crypto: encrypt then decrypt round-trips raw bytes")(withDapr(encryptDecryptBytesRoundTrip))
