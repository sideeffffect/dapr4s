//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js

/** [[ActorContext]] implementation backed by the Dapr actor HTTP API — the Scala.js twin of the JVM `HttpActorContext`,
  * speaking the same routes with the same JSON bodies over the Node-global `fetch` + [[JsAwait]] instead of
  * `HttpURLConnection`.
  *
  * State reads/writes call `/v1.0/actors/{type}/{id}/state[/{key}]`. Reminder and timer registration/cancellation call
  * the matching `/v1.0/actors/{type}/{id}/reminders/{name}` and `/v1.0/actors/{type}/{id}/timers/{name}` endpoints.
  *
  * Instantiated per actor invocation by [[DaprAppServer]]; immutable once constructed.
  *
  * One deliberate difference from the JVM twin's constructor: it takes the whole [[SidecarConfig]] rather than a bare
  * endpoint URI, because the JS raw-fetch precedent ([[ActorCapabilityImpl]]) routes the `dapr-api-token` header
  * through `SidecarConfig.apiToken` — so this context authenticates to a token-protected sidecar, where the JVM twin
  * currently sends no token.
  *
  * @param sidecar
  *   sidecar connection settings; `httpEndpoint` is the base URL of the actor HTTP API (e.g. `"http://localhost:3500"`)
  *   and `apiToken` (when set) is sent as the `dapr-api-token` header on every call
  */
@scala.caps.assumeSafe
private[internal] final class HttpActorContext(
    private val actorType: ActorType,
    private val actorId: ActorId,
    private val sidecar: SidecarConfig,
) extends ActorContext:

  import HttpActorContext.*

  // ---- URL helpers -----------------------------------------------------------

  private def base: String = ActorCapabilityImpl.httpBase(sidecar)

  private def stateUrl(key: ActorStateKey): String =
    s"$base/v1.0/actors/${actorType.value}/${actorId.value}/state/${key.value}"

  private def bulkStateUrl: String =
    s"$base/v1.0/actors/${actorType.value}/${actorId.value}/state"

  private def reminderUrl(name: ReminderName): String =
    s"$base/v1.0/actors/${actorType.value}/${actorId.value}/reminders/${name.value}"

  private def timerUrl(name: TimerName): String =
    s"$base/v1.0/actors/${actorType.value}/${actorId.value}/timers/${name.value}"

  // ---- State -----------------------------------------------------------------

  def get[T: JsonCodec](key: ActorStateKey): Option[T] =
    val url = stateUrl(key)
    val init = new facade.FetchRequestInit(method = "GET", headers = ActorCapabilityImpl.baseHeaders(sidecar))
    val response = JsAwait.await(facade.NodeGlobals.fetch(url, init))
    val code = response.status
    if code == 204 || code == 404 then None
    else
      val text = JsAwait.await(response.text())
      // The JVM twin reads conn.getInputStream here, which throws IOException for any other
      // error status; mirror that by failing loudly instead of silently decoding an error body.
      if code >= 400 then throw new RuntimeException(s"Dapr API error $code at $url: $text")
      else summon[JsonCodec[T]].decode(text).toOption

  def set[T: JsonCodec](key: ActorStateKey, value: T): Unit =
    val requestInner = js.Dictionary[js.Any](
      "key" -> key.value,
      "value" -> js.JSON.parse(summon[JsonCodec[T]].encode(value)),
    )
    val requestObj = js.Dictionary[js.Any]("operation" -> "upsert", "request" -> requestInner)
    postJson(sidecar, bulkStateUrl, js.JSON.stringify(js.Array[js.Any](requestObj)))

  def remove(key: ActorStateKey): Unit =
    val requestInner = js.Dictionary[js.Any]("key" -> key.value)
    val requestObj = js.Dictionary[js.Any]("operation" -> "delete", "request" -> requestInner)
    postJson(sidecar, bulkStateUrl, js.JSON.stringify(js.Array[js.Any](requestObj)))

  // ---- Reminders -------------------------------------------------------------

  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  ): Unit =
    postJson(sidecar, reminderUrl(name), schedulePayload(data, dueTime, period))

  def unregisterReminder(name: ReminderName): Unit =
    deleteRequest(sidecar, reminderUrl(name))

  // ---- Timers ----------------------------------------------------------------

  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  ): Unit =
    postJson(sidecar, timerUrl(name), schedulePayload(data, dueTime, period))

  def unregisterTimer(name: TimerName): Unit =
    deleteRequest(sidecar, timerUrl(name))

@scala.caps.assumeSafe
private object HttpActorContext:

  /** The JSON body shared by reminder and timer registration, exactly as the JVM twin builds it: the payload encoded by
    * its [[JsonCodec]], UTF-8 base64'd into `data`, with `dueTime`/`period` in ISO-8601 duration form (the JVM's
    * `java.time.Duration.ofNanos(...).toString`, e.g. `"PT2S"` — Dapr accepts both ISO-8601 and Go duration strings).
    */
  private def schedulePayload[T: JsonCodec](data: T, dueTime: FiniteDuration, period: Option[FiniteDuration]): String =
    val dataJson = summon[JsonCodec[T]].encode(data)
    val dataBytes = dataJson.getBytes("UTF-8").nn
    val dataBase64 = java.util.Base64.getEncoder.nn.encodeToString(dataBytes).nn
    val fields = js.Dictionary[js.Any]("dueTime" -> toIso(dueTime), "data" -> dataBase64)
    period.foreach(p => fields("period") = toIso(p))
    js.JSON.stringify(fields)

  private def toIso(d: FiniteDuration): String =
    java.time.Duration.ofNanos(d.toNanos).toString

  // ---- HTTP helpers ----------------------------------------------------------

  /** POST `body` as JSON; throw on HTTP >= 400 with the same message shape as the JVM twin's `postJson` (`"Dapr API
    * error $code at $url: $errBody"`). The response body is always consumed (Node's fetch keeps the connection alive
    * until the body is read).
    */
  private def postJson(sidecar: SidecarConfig, url: String, body: String): Unit =
    val init = new facade.FetchRequestInit(
      method = "POST",
      headers = ActorCapabilityImpl.baseHeaders(sidecar),
      body = body,
    )
    val response = JsAwait.await(facade.NodeGlobals.fetch(url, init))
    val text = JsAwait.await(response.text())
    if response.status >= 400 then throw new RuntimeException(s"Dapr API error ${response.status} at $url: $text")

  /** DELETE with the status deliberately ignored, mirroring the JVM twin (`val _ = conn.getResponseCode`):
    * unregistering a missing reminder/timer is a documented no-op.
    */
  private def deleteRequest(sidecar: SidecarConfig, url: String): Unit =
    val init = new facade.FetchRequestInit(method = "DELETE", headers = ActorCapabilityImpl.baseHeaders(sidecar))
    val response = JsAwait.await(facade.NodeGlobals.fetch(url, init))
    val _ = JsAwait.await(response.text())
