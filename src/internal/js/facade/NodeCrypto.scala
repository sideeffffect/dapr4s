//> using target.platform "scala-js"
package dapr4s.internal.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Facade for the Node.js built-in `node:crypto` module — only the one-shot hashing subset needed by the deterministic
  * `WorkflowContext.newUuid` implementation (`dapr4s.internal.WorkflowContextImpl` on Scala.js).
  *
  * The JS internal layer already requires Node (the Dapr JS SDK itself is Node-only), so depending on a Node built-in
  * here adds no new platform constraint. `java.security.MessageDigest` is not part of the Scala.js javalib, which is
  * why SHA-1 comes from the host platform instead.
  */
@js.native
@JSImport("node:crypto", JSImport.Namespace)
private[internal] object NodeCrypto extends js.Object:
  def createHash(algorithm: String): NodeHash = js.native

/** The `Hash` object returned by `crypto.createHash` — used in the chained one-shot form
  * `createHash("sha1").update(data, "utf8").digest("hex")`.
  */
@js.native
private[internal] trait NodeHash extends js.Object:
  def update(data: String, inputEncoding: String): NodeHash = js.native
  def digest(encoding: String): String = js.native
