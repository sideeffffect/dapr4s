//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import dapr4s.actor.*
import java.net.URI
import scala.concurrent.duration.FiniteDuration
import unsafeExceptions.canThrowAny
import scala.util.control.NonFatal

/** [[ActorContext]] implementation backed by the Dapr actor HTTP API.
  *
  * State reads/writes call `/v1.0/actors/{type}/{id}/state[/{key}]`. Reminder and timer registration/cancellation call
  * the matching `/v1.0/actors/{type}/{id}/reminders/{name}` and `/v1.0/actors/{type}/{id}/timers/{name}` endpoints.
  *
  * Instantiated per actor invocation; immutable once constructed.
  *
  * @param sidecarHttpEndpoint
  *   Base URL of the Dapr sidecar HTTP API (e.g. `"http://localhost:3500"`).
  */
@scala.caps.assumeSafe
private[internal] final class HttpActorContext(
    private val actorType: ActorType,
    private val actorId: ActorId,
    private val sidecarHttpEndpoint: URI,
) extends ActorContext:

  import HttpActorContext.*

  // ---- URL helpers -----------------------------------------------------------

  private def stateUrl(key: ActorStateKey): String =
    s"$sidecarHttpEndpoint/v1.0/actors/${actorType.value}/${actorId.value}/state/${key.value}"

  private def bulkStateUrl: String =
    s"$sidecarHttpEndpoint/v1.0/actors/${actorType.value}/${actorId.value}/state"

  private def reminderUrl(name: ReminderName): String =
    s"$sidecarHttpEndpoint/v1.0/actors/${actorType.value}/${actorId.value}/reminders/${name.value}"

  private def timerUrl(name: TimerName): String =
    s"$sidecarHttpEndpoint/v1.0/actors/${actorType.value}/${actorId.value}/timers/${name.value}"

  // ---- State -----------------------------------------------------------------

  def get[T: JsonCodec](key: ActorStateKey): Option[T] =
    // guard: a connection failure (IOException) becomes DaprApiException("UNAVAILABLE"), like every other sidecar call.
    val json = JvmErrors.guard(ErrorContext.Runtime) {
      val conn = openConn(stateUrl(key))
      try
        conn.setRequestMethod("GET")
        conn.connect()
        val code = conn.getResponseCode
        if code == 204 || code == 404 then None
        else if code >= 400 then throw JvmErrors.fromHttp(ErrorContext.Runtime, code, readError(conn))
        else Some(readStream(conn.getInputStream.nn))
      finally conn.disconnect()
    }
    json.flatMap(summon[JsonCodec[T]].decode(_).toOption)

  def set[T: JsonCodec](key: ActorStateKey, value: T): Unit =
    val requestInner = Json.mapper.createObjectNode()
    requestInner.put("key", key.value)
    requestInner.set("value", Json.mapper.readTree(summon[JsonCodec[T]].encode(value)))
    val requestObj = Json.mapper.createObjectNode()
    requestObj.put("operation", "upsert")
    requestObj.set("request", requestInner)
    val body = Json.mapper.writeValueAsString(Json.mapper.createArrayNode().add(requestObj))
    postJson(bulkStateUrl, body)

  def remove(key: ActorStateKey): Unit =
    val requestInner = Json.mapper.createObjectNode()
    requestInner.put("key", key.value)
    val requestObj = Json.mapper.createObjectNode()
    requestObj.put("operation", "delete")
    requestObj.set("request", requestInner)
    val body = Json.mapper.writeValueAsString(Json.mapper.createArrayNode().add(requestObj))
    postJson(bulkStateUrl, body)

  // ---- Reminders -------------------------------------------------------------

  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  ): Unit =
    val dataJson = summon[JsonCodec[T]].encode(data)
    val dataBytes = dataJson.getBytes("UTF-8").nn
    val dataBase64 = java.util.Base64.getEncoder.nn.encodeToString(dataBytes).nn
    val fields = Json.mapper.createObjectNode()
    fields.put("dueTime", toIso(dueTime))
    fields.put("data", dataBase64)
    period.foreach(p => fields.put("period", toIso(p)))
    postJson(reminderUrl(name), Json.mapper.writeValueAsString(fields))

  def unregisterReminder(name: ReminderName): Unit =
    deleteRequest(reminderUrl(name))

  // ---- Timers ----------------------------------------------------------------

  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  ): Unit =
    val dataJson = summon[JsonCodec[T]].encode(data)
    val dataBytes = dataJson.getBytes("UTF-8").nn
    val dataBase64 = java.util.Base64.getEncoder.nn.encodeToString(dataBytes).nn
    val fields = Json.mapper.createObjectNode()
    fields.put("dueTime", toIso(dueTime))
    fields.put("data", dataBase64)
    period.foreach(p => fields.put("period", toIso(p)))
    postJson(timerUrl(name), Json.mapper.writeValueAsString(fields))

  def unregisterTimer(name: TimerName): Unit =
    deleteRequest(timerUrl(name))

@scala.caps.assumeSafe
private object HttpActorContext:
  // ---- HTTP helpers ----------------------------------------------------------

  private def openConn(url: String): java.net.HttpURLConnection =
    java.net.URI
      .create(url)
      .toURL
      .nn
      .openConnection()
      .asInstanceOf[java.net.HttpURLConnection]

  private def readStream(in: java.io.InputStream): String =
    new String(in.readAllBytes().nn, "UTF-8")

  private def readError(conn: java.net.HttpURLConnection): String =
    val errStream = conn.getErrorStream
    if errStream != null then new String(errStream.nn.readAllBytes().nn, "UTF-8") else ""

  private def postJson(url: String, body: String): Unit = JvmErrors.guard(ErrorContext.Runtime) {
    val conn = openConn(url)
    try
      conn.setRequestMethod("POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      val bytes = body.getBytes("UTF-8").nn
      conn.getOutputStream.nn.write(bytes)
      conn.getOutputStream.nn.close()
      val code = conn.getResponseCode
      if code >= 400 then throw JvmErrors.fromHttp(ErrorContext.Runtime, code, readError(conn))
    finally conn.disconnect()
  }

  private def toIso(d: FiniteDuration): String =
    java.time.Duration.ofNanos(d.toNanos).toString

  private def deleteRequest(url: String): Unit = JvmErrors.guard(ErrorContext.Runtime) {
    val conn = openConn(url)
    try
      conn.setRequestMethod("DELETE")
      val _ = conn.getResponseCode
    finally conn.disconnect()
  }
