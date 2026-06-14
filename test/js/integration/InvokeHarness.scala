//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import JsItEnv.*

/** Scala.js bring-up for the shared [[InvokeItTest]] — the JS implementation of the cross-platform `InvokeHarness` (the
  * JVM one lives in test/jvm). The invoke routes are hosted by the in-process [[JsItServerApp]] union server that
  * [[ServerDaprJsItSuite]] starts; `withDapr` comes from there. Supplies the [[InvokeScenarios]] hooks: [[serverAppId]]
  * and a `retrying` that retries the first call, because daprd reports healthy slightly before the JS app channel
  * finishes warming up.
  */
@scala.caps.assumeSafe
trait InvokeHarness extends ServerDaprJsItSuite:
  protected def serverAppId: AppId = ServerAppId
  protected def retrying[T](label: String)(body: => T): T = retryUntilSuccess(label)(body)
