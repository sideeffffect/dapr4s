package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.Assertions
import unsafeExceptions.canThrowAny

/** Direct-call [[CryptoCapability]] scenarios shared by the JVM and JS integration suites, against the canonical
  * `crypto.dapr.localstorage` store backed by a fresh RSA key (`rsa-key`). Crypto is gRPC-only in the JS SDK, so the JS
  * twin exercises the gRPC alpha1 streaming wire API.
  */
trait CryptoScenarios:
  self: Assertions =>

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
