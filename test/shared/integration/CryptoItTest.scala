package dapr4s.test.integration

import munit.FunSuite

/** [[dapr4s.CryptoCapability]] integration suite — a SINGLE cross-platform entry point over the shared
  * [[CryptoSuiteDef]] and the per-platform [[SharedDaprItSuite]] bring-up (canonical `crypto.dapr.localstorage` store
  * backed by a fresh RSA key). Crypto is gRPC-only in the JS SDK, so on Scala.js this exercises the lazily created
  * gRPC-protocol client over the real alpha1 streaming wire API.
  */
@scala.caps.assumeSafe
class CryptoItTest extends FunSuite, SharedDaprItSuite, CryptoSuiteDef
