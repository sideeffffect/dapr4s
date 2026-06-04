package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import io.dapr.testcontainers.{Component, DaprContainer}
import com.dimafeng.testcontainers.munit.TestContainersForAll
import munit.FunSuite
import unsafeExceptions.canThrowAny

import java.security.KeyPairGenerator
import java.util.Base64

/** Tests for [[CryptoCapability]] against Dapr's `crypto.dapr.localstorage` component, backed by an RSA key generated
  * at test time and mounted into the container. Verifies the encrypt → decrypt round trip over the real alpha1
  * streaming wire API.
  */
@scala.caps.assumeSafe
class CryptoCapabilityServerTest extends FunSuite with TestContainersForAll:

  type Containers = DaprTestContainer

  private val KeyName = "rsa-key"

  override def startContainers(): DaprTestContainer =
    val keyDir = java.nio.file.Files.createTempDirectory("dapr4s-crypto-keys").nn
    val keyFile = keyDir.resolve(KeyName)
    java.nio.file.Files.write(keyFile, generateRsaPrivateKeyPem().getBytes("UTF-8").nn)
    // daprd runs as a non-root user inside the container; make the dir/key world-readable so the
    // crypto component can load the key (otherwise: "open /keys/rsa-key: permission denied").
    java.nio.file.Files
      .setPosixFilePermissions(keyDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    java.nio.file.Files
      .setPosixFilePermissions(keyFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))

    val c = DaprTestContainer(
      DaprContainer(DaprTestContainer.DefaultImage)
        .withAppName("crypto-server-test")
        .withAppPort(0)
        .withCopyFileToContainer(
          org.testcontainers.utility.MountableFile.forHostPath(keyDir, 0x1ed), // 0755
          "/keys",
        )
        .withComponent(Component("localstorage", "crypto.dapr.localstorage", "v1", java.util.Map.of("path", "/keys"))),
    )
    c.start()
    c

  private def generateRsaPrivateKeyPem(): String =
    val kpg = KeyPairGenerator.getInstance("RSA").nn
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair().nn
    val der = kp.getPrivate.nn.getEncoded.nn
    val b64 = Base64.getMimeEncoder(64, Array[Byte]('\n')).nn.encodeToString(der)
    s"-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n"

  test("crypto: encryptString then decryptString round-trips the original text"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.crypto(CryptoComponentName("localstorage")) {
          val plaintext = "the quick brown fox"
          val cipher = CryptoCapability.encryptString(CryptoKeyName(KeyName), plaintext, KeyWrapAlgorithm.Rsa)
          assert(cipher.nonEmpty, "ciphertext should not be empty")
          assertEquals(CryptoCapability.decryptString(cipher), plaintext)
        }
    }

  test("crypto: encrypt then decrypt round-trips raw bytes"):
    withContainers { c =>
      Dapr.runWithEndpoints(c.httpEndpoint, c.grpcEndpoint):
        DaprCapability.crypto(CryptoComponentName("localstorage")) {
          val data = Charsets.encodeString("payload-bytes", Charsets.Utf8)
          val cipher = CryptoCapability.encrypt(CryptoKeyName(KeyName), data, KeyWrapAlgorithm.Rsa)
          assertEquals(CryptoCapability.decrypt(cipher), data)
        }
    }
