//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import munit.FunSuite
import JsItEnv.*

/** Scala.js (Wasm+JSPI) [[InvokeCapability]] integration suite: a one-line-ish entry point over the shared
  * [[InvokeSuiteDef]] (registrations + scenarios), run against the in-process [[JsItServerApp]]'s invoke routes (hosted
  * by [[ServerDaprJsItSuite]]) via the live sidecar. The JVM twin [[InvokeItTest]] runs the very same suite definition.
  *
  * Supplies the two platform hooks [[InvokeScenarios]] leaves abstract: [[serverAppId]] and a `retrying` that retries
  * the first call, because daprd reports healthy slightly before the JS app channel finishes warming up.
  */
@scala.caps.assumeSafe
class InvokeJsIntegrationTest extends FunSuite, ServerDaprJsItSuite, InvokeSuiteDef:

  protected def serverAppId: AppId = ServerAppId
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)
