//> using target.platform "scala-js"
package dapr4s.test.integration

import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Small hand-written Scala.js facades the JS integration harness needs that the ScalablyTyped conversion does not
  * provide directly:
  *
  *   - the Node builtins used to render the canonical Dapr component set at test runtime
  *     (`node:fs`/`node:os`/`node:path`) and to mint a fresh RSA key for the cryptostore (`node:crypto`) — the JS
  *     analogues of what `JvmItComponents` does with `java.nio` + `java.security.KeyPairGenerator`;
  *   - `TestContainers.exposeHostPorts`, the one `testcontainers` public static that did NOT survive ScalablyTyped
  *     conversion (its rest-param signature was dropped; only the private `isHostPortExposed` field came through). It
  *     is the twin of the JVM `org.testcontainers.Testcontainers.exposeHostPorts` the server-delivery suites call so
  *     the in-process app server is reachable from inside the daprd container.
  *
  * Same hand-shim rationale as `dapr4s.internal.facade.ExpressModule` in the main JS layer.
  */

@js.native
@JSImport("node:fs", JSImport.Namespace)
private[integration] object NodeFs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native
  def writeFileSync(path: String, data: String): Unit = js.native
  def mkdtempSync(prefix: String): String = js.native
  def mkdirSync(path: String, options: js.Object): js.Any = js.native
  def existsSync(path: String): Boolean = js.native

@js.native
@JSImport("node:os", JSImport.Namespace)
private[integration] object NodeOs extends js.Object:
  def tmpdir(): String = js.native

@js.native
@JSImport("node:path", JSImport.Namespace)
private[integration] object NodePath extends js.Object:
  def join(parts: String*): String = js.native
  def dirname(p: String): String = js.native

@js.native
@JSImport("node:crypto", JSImport.Namespace)
private[integration] object NodeCrypto extends js.Object:
  /** `generateKeyPairSync("rsa", { modulusLength, publicKeyEncoding, privateKeyEncoding })`; with PEM encodings
    * configured the result's `privateKey`/`publicKey` are strings.
    */
  def generateKeyPairSync(typ: String, options: js.Object): js.Dynamic = js.native

@js.native
@JSImport("node:process", JSImport.Namespace)
private[integration] object NodeProcess extends js.Object:
  def cwd(): String = js.native
  def env: js.Dictionary[js.UndefOr[String]] = js.native

/** `testcontainers`' `TestContainers.exposeHostPorts(...ports)` — see file header. */
@js.native
@JSImport("testcontainers", "TestContainers")
private[integration] object TestContainersStatics extends js.Object:
  def exposeHostPorts(ports: Int*): js.Promise[Unit] = js.native
