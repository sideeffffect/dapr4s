package dapr.safe.internal

import dapr.safe.*
import unsafeExceptions.canThrowAny
import scala.util.control.NonFatal

/** [[ActorContext]] implementation backed by the Dapr actor HTTP API.
  *
  * State reads/writes call `/v1.0/actors/{type}/{id}/state[/{key}]`. Reminder and timer registration/cancellation call
  * the matching `/v1.0/actors/{type}/{id}/reminders/{name}` and `/v1.0/actors/{type}/{id}/timers/{name}` endpoints.
  *
  * Instantiated per actor invocation; immutable once constructed.
  */
@scala.caps.assumeSafe
private[safe] final class HttpActorContext(
    private val actorType: String,
    private val actorId: String,
    private val daprHttpPort: Int,
) extends ActorContext:

  // ---- URL helpers -----------------------------------------------------------

  private def stateUrl(key: String): String =
    s"http://localhost:$daprHttpPort/v1.0/actors/$actorType/$actorId/state/$key"

  private def bulkStateUrl: String =
    s"http://localhost:$daprHttpPort/v1.0/actors/$actorType/$actorId/state"

  private def reminderUrl(name: String): String =
    s"http://localhost:$daprHttpPort/v1.0/actors/$actorType/$actorId/reminders/$name"

  private def timerUrl(name: String): String =
    s"http://localhost:$daprHttpPort/v1.0/actors/$actorType/$actorId/timers/$name"

  // ---- State -----------------------------------------------------------------

  def get[T: JsonCodec](key: String): Option[T] =
    val conn = openConn(stateUrl(key))
    try
      conn.setRequestMethod("GET")
      conn.connect()
      val code = conn.getResponseCode
      if code == 204 || code == 404 then None
      else
        val json = readStream(conn.getInputStream.nn)
        summon[JsonCodec[T]].decode(json).toOption
    finally conn.disconnect()

  def set[T: JsonCodec](key: String, value: T): Unit =
    val body = ujson.write(
      ujson.Arr(
        ujson.Obj(
          "operation" -> "upsert",
          "request"   -> ujson.Obj("key" -> key, "value" -> ujson.read(summon[JsonCodec[T]].encode(value))),
        ),
      ),
    )
    postJson(bulkStateUrl, body)

  def remove(key: String): Unit =
    val body = ujson.write(
      ujson.Arr(ujson.Obj("operation" -> "delete", "request" -> ujson.Obj("key" -> key))),
    )
    postJson(bulkStateUrl, body)

  // ---- Reminders -------------------------------------------------------------

  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: java.time.Duration,
      period: Option[java.time.Duration] = None,
  ): Unit =
    val dataJson   = summon[JsonCodec[T]].encode(data)
    val dataBytes  = dataJson.getBytes("UTF-8").nn
    val dataBase64 = java.util.Base64.getEncoder.nn.encodeToString(dataBytes).nn
    val fields     = ujson.Obj(
      "dueTime" -> dueTime.toString,
      "data"    -> dataBase64,
    )
    period.foreach(p => fields("period") = p.toString)
    postJson(reminderUrl(name.value), ujson.write(fields))

  def unregisterReminder(name: ReminderName): Unit =
    deleteRequest(reminderUrl(name.value))

  // ---- Timers ----------------------------------------------------------------

  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: java.time.Duration,
      period: Option[java.time.Duration] = None,
  ): Unit =
    val dataJson   = summon[JsonCodec[T]].encode(data)
    val dataBytes  = dataJson.getBytes("UTF-8").nn
    val dataBase64 = java.util.Base64.getEncoder.nn.encodeToString(dataBytes).nn
    val fields     = ujson.Obj(
      "dueTime" -> dueTime.toString,
      "data"    -> dataBase64,
    )
    period.foreach(p => fields("period") = p.toString)
    postJson(timerUrl(name.value), ujson.write(fields))

  def unregisterTimer(name: TimerName): Unit =
    deleteRequest(timerUrl(name.value))

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

  private def postJson(url: String, body: String): Unit =
    val conn = openConn(url)
    try
      conn.setRequestMethod("POST")
      conn.setDoOutput(true)
      conn.setRequestProperty("Content-Type", "application/json")
      val bytes = body.getBytes("UTF-8").nn
      conn.getOutputStream.nn.write(bytes)
      conn.getOutputStream.nn.close()
      val _ = conn.getResponseCode
    finally conn.disconnect()

  private def deleteRequest(url: String): Unit =
    val conn = openConn(url)
    try
      conn.setRequestMethod("DELETE")
      val _ = conn.getResponseCode
    finally conn.disconnect()
