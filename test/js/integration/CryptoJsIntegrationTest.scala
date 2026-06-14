//> using target.platform "scala-js"
package dapr4s.test.integration

import munit.FunSuite

/** Scala.js (Wasm+JSPI) [[CryptoCapability]] integration suite: a one-line entry point over the shared
  * [[CryptoSuiteDef]] (registrations + scenarios), run against the canonical `crypto.dapr.localstorage` store (backed
  * by a fresh RSA key mounted into the sidecar by [[SharedDaprJsItSuite]]). The JVM twin [[CryptoItTest]] runs the very
  * same suite definition.
  *
  * Crypto is gRPC-only in the JS SDK (the HTTP client throws `HTTPNotSupportedError`), so this suite exercises the
  * lazily created gRPC-protocol client over the real alpha1 streaming wire API.
  */
@scala.caps.assumeSafe
class CryptoJsIntegrationTest extends FunSuite, SharedDaprJsItSuite, CryptoSuiteDef
