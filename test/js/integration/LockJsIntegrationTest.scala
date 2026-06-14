//> using target.platform "scala-js"
package dapr4s.test.integration

import munit.FunSuite

/** Scala.js (Wasm+JSPI) [[LockCapability]] integration suite: a one-line entry point over the shared [[LockSuiteDef]]
  * (registrations + scenarios), run against the canonical `lock.redis` store via a sidecar started in-process by
  * [[SharedDaprJsItSuite]]. The JVM twin [[LockItTest]] runs the very same suite definition.
  */
@scala.caps.assumeSafe
class LockJsIntegrationTest extends FunSuite, SharedDaprJsItSuite, LockSuiteDef
