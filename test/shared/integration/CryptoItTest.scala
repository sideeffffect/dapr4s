package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** [[dapr4s.CryptoCapability]] integration suite — a SINGLE cross-platform file, run via the per-platform
  * [[SharedDaprItSuite]] bring-up against the canonical `crypto.dapr.localstorage` store backed by a fresh RSA key
  * (`rsa-key`). Crypto is gRPC-only in the JS SDK, so on Scala.js this exercises the lazily created gRPC alpha1
  * streaming wire API.
  */
@scala.caps.assumeSafe
class CryptoItTest extends FunSuite, SharedDaprItSuite:

  test("crypto: encryptString then decryptString round-trips the original text")(
    withDapr(encryptDecryptStringRoundTrip),
  )
  test("crypto: encrypt then decrypt round-trips raw bytes")(withDapr(encryptDecryptBytesRoundTrip))

  def encryptDecryptStringRoundTrip(using DaprCapability): Unit =
    DaprCapability.crypto(ItNames.CryptoStore):
      val plaintext = "the quick brown fox"
      val cipher = CryptoCapability.encryptString(ItNames.CryptoKey, plaintext, KeyWrapAlgorithm.Rsa)
      assert(cipher.nonEmpty, "ciphertext should not be empty")
      assertEquals(CryptoCapability.decryptString(cipher), plaintext)

  def encryptDecryptBytesRoundTrip(using DaprCapability): Unit =
    DaprCapability.crypto(ItNames.CryptoStore):
      val data = Charsets.encodeString("payload-bytes", Charsets.Utf8)
      val cipher = CryptoCapability.encrypt(ItNames.CryptoKey, data, KeyWrapAlgorithm.Rsa)
      assertEquals(CryptoCapability.decrypt(cipher), data)
