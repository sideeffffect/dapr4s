package dapr4s.test.integration

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import dapr4s.given
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Cross-platform contract + conveniences for the service-level integration suites (Order / Inventory / end-to-end).
  *
  * These suites host a real [[dapr4s.internal.DaprAppServer]] and poke it DIRECTLY over HTTP — invoke routes at
  * `/<method>`, pub/sub subscriptions at `/<topic>` (the sidecar's own CloudEvent envelope). The server is NOT the
  * sidecar's app channel, so the only pub/sub delivery is the test's direct CloudEvent POST (synchronous, no polling) —
  * identical on both platforms, and free of the double-delivery a real sidecar subscription would add. The handlers
  * still use a real sidecar (via [[daprScope]]) for their outbound state/lock/publish calls.
  *
  * The three abstract members are the only platform-specific part; the per-platform `ServiceHarness` (testcontainers +
  * a per-test `DaprAppServer` thread on the JVM; a shared `serveAsync` server + `fetch` on Scala.js) implements them,
  * and the suite classes in test/shared mix it in. WHY @assumeSafe: [[daprScope]] hands back a captured
  * [[DaprCapability]] and the conveniences eta-expand handler closures — the standard test-side erasure.
  */
@scala.caps.assumeSafe
trait ServiceHarnessApi:
  self: FunSuite =>

  /** Host `appOf(scope)` reachable over plain HTTP with `scope` bound to a real sidecar, then run `body` with that
    * `scope` in context (`summon[DaprCapability]` reads persisted state directly; [[invoke]] / [[deliver]] are valid
    * throughout). JVM: a fresh per-test server; JS: the shared service server (which already hosts the union of the
    * service apps — `appOf` is only consulted on the JVM).
    */
  protected def withService(appOf: DaprCapability ?=> DaprApp)(body: DaprCapability ?=> Unit): Any

  /** POST `reqBody` to `http://<app server>/<path>` and return `(statusCode, responseBody)`. */
  protected def invokeRaw(path: String, reqBody: String): (Int, String)

  /** Invoke an [[InvokeRoute]] by method name: encode `req`, POST to `/<method>`, decode the response. */
  protected final def invoke[Req: JsonCodec, Resp: JsonCodec](method: String, req: Req): Resp =
    JsonCodec.decodeOrThrow[Resp](invokeRaw(method, summon[JsonCodec[Req]].encode(req))._2)

  /** Deliver a CloudEvent to a [[Subscription]] route: wrap `data` in the sidecar's envelope and POST to `/<topic>`.
    * Returns the raw response (typically `{"status":"SUCCESS"}`). Synchronous on both platforms.
    */
  protected final def deliver[T: JsonCodec](
      topic: String,
      pubsubName: String,
      data: T,
      eventId: String = "test-event-id",
  ): String =
    val dataJson = summon[JsonCodec[T]].encode(data)
    val body =
      s"""{"id":"$eventId","source":"test","specversion":"1.0","type":"test.event",""" +
        s""""topic":"$topic","pubsubname":"$pubsubName","datacontenttype":"application/json","data":$dataJson}"""
    invokeRaw(topic, body)._2
