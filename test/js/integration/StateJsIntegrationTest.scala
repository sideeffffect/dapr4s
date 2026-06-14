//> using target.platform "scala-js"
package dapr4s.test.integration

import munit.FunSuite

/** Scala.js (Wasm+JSPI) [[StateCapability]] integration suite: a one-line entry point over the shared [[StateSuiteDef]]
  * (registrations + scenarios), run against the canonical `state.redis` component via a sidecar started in-process by
  * [[SharedDaprJsItSuite]]. The JVM twin [[StateItTest]] runs the very same suite definition — only bring-up and the
  * `withDapr` (`js.async{}.toFuture`) boundary differ.
  */
@scala.caps.assumeSafe
class StateJsIntegrationTest extends FunSuite, SharedDaprJsItSuite, StateSuiteDef
