//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.*
import dapr4s.given
import java.net.URI
import scala.scalajs.js
import scala.util.control.NonFatal
import unsafeExceptions.canThrowAny

/** Shared constants and polling helpers for the Scala.js integration suites and the [[jsTestServerMain]] test server
  * they talk to.
  *
  * ==Port map (single source of truth on the Scala side)==
  * The infra twin of this table lives in `scripts/js-integration-env.sh` — keep the two in sync. All ports are
  * non-default to avoid collisions with a locally `dapr init`-ed stack (see the script header for the full map
  * including placement/scheduler/metrics).
  *
  * ==Why polling helpers==
  * The JVM integration suites poll for sidecar-startup effects (placement table dissemination, workflow runtime
  * registration, pub/sub delivery). The same applies here, but "sleeping" on a single-threaded JS runtime means
  * suspending on a timer promise — routed through [[dapr4s.internal.JsAwait]], the one sanctioned home of orphan
  * `js.await` (AGENTS.md: never import `allowOrphanJSAwait` anywhere else). These helpers therefore only work on the
  * Wasm+JSPI backend, like the capability calls around them.
  *
  * WHY @assumeSafe: the by-name `body`/`probe` parameters are pure function types under `pureFunctions`, but callers
  * pass closures that capture Dapr capabilities from the enclosing `Dapr.run` scope — the standard test-side erasure
  * the JVM suites rely on as well (their suite classes are `@assumeSafe` for the same reason). Safe because the
  * closures never outlive the `run` block that owns the capabilities.
  */
@scala.caps.assumeSafe
object JsItEnv:

  val DaprHttpPort: Int = 3591
  val DaprGrpcPort: Int = 50191
  val AppPort: Int = 8391
  val ServerAppId: AppId = AppId("js-it-server")

  // Component names match the shared canonical set scripts/it/components/<name>.yaml.
  val StateStore: StateStoreName = StateStoreName("statestore")
  val PubSub: PubSubName = PubSubName("pubsub")
  val LockStore: LockStoreName = LockStoreName("lockstore")
  val ConfigStore: ConfigurationStoreName = ConfigurationStoreName("configstore")
  val SecretStore: SecretStoreName = SecretStoreName("secretstore")
  val CryptoStore: CryptoComponentName = CryptoComponentName("cryptostore")
  val CryptoKey: CryptoKeyName = CryptoKeyName("rsa-key")

  /** Client config pointing at the harness sidecar; every suite's `Dapr(...)` uses this. */
  def clientConfig: DaprConfig = DaprConfig(
    sidecar = SidecarConfig(
      httpEndpoint = URI.create(s"http://localhost:$DaprHttpPort"),
      grpcEndpoint = URI.create(s"http://localhost:$DaprGrpcPort"),
    ),
  )

  /** Server config for [[jsTestServerMain]]: same sidecar, app server on [[AppPort]]. */
  def serverConfig: DaprConfig = clientConfig.copy(appServer = AppServerConfig(port = DaprPort(AppPort)))

  /** Unique-enough id for test resources. NOT `java.util.UUID.randomUUID()`: that does not '''link''' on Scala.js (it
    * reaches for `java.security.SecureRandom`, which the Scala.js javalib does not provide). Test ids need uniqueness
    * across a single harness run, not cryptographic strength.
    */
  def uniqueId(): String =
    s"${System.currentTimeMillis()}-${(js.Math.random() * 1e9).toLong}"

  /** Suspend the current Wasm stack for `ms` milliseconds (the JS analogue of `Thread.sleep`). */
  def sleep(ms: Int): Unit =
    dapr4s.internal.JsAwait.await(new js.Promise[Unit]((resolve, _) => {
      js.timers.setTimeout(ms.toDouble) { resolve(()); () }: Unit
      ()
    }))

  /** Poll `probe` until it returns `Some`, sleeping `intervalMs` between attempts; fail the test after `timeoutMs`. */
  def eventually[T](label: String, timeoutMs: Int = 30000, intervalMs: Int = 250)(probe: => Option[T]): T =
    val deadline = System.currentTimeMillis() + timeoutMs
    var result: Option[T] = probe
    while result.isEmpty && System.currentTimeMillis() < deadline do
      sleep(intervalMs)
      result = probe
    result.getOrElse(throw new AssertionError(s"eventually($label) timed out after ${timeoutMs}ms"))

  /** Retry `body` until it stops throwing (returning its value), for sidecar-startup races: actor placement table
    * dissemination and workflow runtime registration both surface as 500s until ready — exactly what the JVM twins poll
    * through (`ActorCapabilityServerTest.waitForCount`, `WorkflowCapabilityServerTest.waitForWorkflowRuntime`).
    * Rethrows the last failure after `timeoutMs`.
    */
  def retryUntilSuccess[T](label: String, timeoutMs: Int = 60000, intervalMs: Int = 500)(body: => T): T =
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable | Null = null
    while System.currentTimeMillis() < deadline do
      try return body
      catch
        case NonFatal(e) =>
          last = e
          sleep(intervalMs)
    val l = last
    throw new AssertionError(
      s"retryUntilSuccess($label) still failing after ${timeoutMs}ms: ${
          if l == null then "no attempt ran" else l.toString
        }",
    )
