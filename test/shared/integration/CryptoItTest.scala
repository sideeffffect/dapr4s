package dapr4s.test.integration

import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.CryptoCapability]] integration suite — a SINGLE cross-platform file over the shared [[CryptoScenarios]] and
  * the per-platform [[SharedDaprItSuite]] bring-up (canonical `crypto.dapr.localstorage` store backed by a fresh RSA
  * key). Crypto is gRPC-only in the JS SDK, so on Scala.js this exercises the lazily created gRPC-protocol client over
  * the real alpha1 streaming wire API.
  */
@scala.caps.assumeSafe
class CryptoItTest extends FunSuite, SharedDaprItSuite, CryptoScenarios:

  test("crypto: encryptString then decryptString round-trips the original text")(
    withDapr(encryptDecryptStringRoundTrip),
  )
  test("crypto: encrypt then decrypt round-trips raw bytes")(withDapr(encryptDecryptBytesRoundTrip))
