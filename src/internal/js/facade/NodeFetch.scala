//> using target.platform "scala-js"
package dapr4s.internal.facade

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

/** Facade for the WHATWG `fetch` available as a Node global since Node 18 (the same floor the Dapr JS SDK requires).
  *
  * Used by the parts of the JS internal layer that must talk to the sidecar HTTP API directly because the SDK cannot
  * express the operation (see [[dapr4s.internal.ActorCapabilityImpl]] and
  * [[dapr4s.internal.StateCapabilityImpl.getWithETag]]) — the JS analogue of the JVM `HttpActorContext` raw
  * `HttpURLConnection` precedent.
  */
@js.native
@JSGlobalScope
private[internal] object NodeGlobals extends js.Object:
  def fetch(url: String, init: FetchRequestInit): js.Promise[FetchResponse] = js.native

/** The `RequestInit` subset we need. */
private[internal] final class FetchRequestInit(
    val method: String,
    val headers: js.Dictionary[String],
    val body: js.UndefOr[String] = js.undefined,
) extends js.Object

/** The `Response` subset we need. `headers.get(name)` returns `null` for absent headers — declared as `String | Null`
  * so explicit-nulls forces callers to handle absence.
  */
@js.native
private[internal] trait FetchResponse extends js.Object:
  def status: Int = js.native
  def text(): js.Promise[String] = js.native
  def headers: FetchHeaders = js.native

@js.native
private[internal] trait FetchHeaders extends js.Object:
  def get(name: String): String | Null = js.native
