//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import munit.FunSuite
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}
import scala.scalajs.js
import unsafeExceptions.canThrowAny
import JsItEnv.*

/** Scala.js (Wasm+JSPI) [[CryptoCapability]] integration suite: a thin shell over the shared [[CryptoScenarios]], run
  * against the canonical `crypto.dapr.localstorage` store (backed by a fresh RSA key mounted into the sidecar) via the
  * live sidecar. The JVM twin [[CryptoItTest]] runs the same scenarios.
  *
  * Crypto is gRPC-only in the JS SDK (the HTTP client throws `HTTPNotSupportedError`), so this suite exercises the
  * lazily created gRPC-protocol client over the real alpha1 streaming wire API.
  */
@scala.caps.assumeSafe
class CryptoJsIntegrationTest extends FunSuite, CryptoScenarios:

  override def munitTimeout: Duration = 120.seconds

  private def run(body: DaprCapability ?=> Unit): Future[Unit] =
    js.async(Dapr(clientConfig).run(body)).toFuture

  test("crypto: encryptString then decryptString round-trips the original text")(run(encryptDecryptStringRoundTrip))
  test("crypto: encrypt then decrypt round-trips raw bytes")(run(encryptDecryptBytesRoundTrip))
