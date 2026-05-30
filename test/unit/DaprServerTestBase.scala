package dapr4s.test.unit

import dapr4s.*
import dapr4s.given
import dapr4s.internal.DaprAppServer
import unsafeExceptions.canThrowAny

/** Shared HTTP helpers and server lifecycle for DaprAppServer-based unit tests.
  *
  * Mix into a [[munit.FunSuite]] subclass. Each `withServer` call starts a real [[DaprAppServer]] on an ephemeral port,
  * waits for it to accept connections, runs the test body, then interrupts the server thread.
  */
@scala.caps.assumeSafe
trait DaprServerTestBase:

  protected def withServer[T](app: DaprApp)(f: Int => T): T =
    val port = freePort()
    val server = new DaprAppServer(app)
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      f(port)
    finally
      thread.interrupt()
      thread.join(2000)

  protected def freePort(): Int =
    val sock = java.net.ServerSocket(0)
    val p = sock.getLocalPort
    sock.close()
    p

  protected def waitForPort(port: Int, maxMs: Int = 3000): Unit =
    val deadline = System.currentTimeMillis() + maxMs
    while System.currentTimeMillis() < deadline do
      try
        val sock = java.net.Socket("localhost", port)
        sock.close()
        return
      catch case _: java.io.IOException => Thread.sleep(20)
    throw RuntimeException(s"Port $port did not open within ${maxMs}ms")

  protected def httpGet(url: String): String =
    val conn = java.net.URI(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.connect()
    val code = conn.getResponseCode
    val stream = if code < 400 then conn.getInputStream.nn else conn.getErrorStream.nn
    new String(stream.readAllBytes().nn, "UTF-8")

  /** Invoke a [[dapr4s.InvocationRoute]] through a running [[dapr4s.internal.DaprAppServer]].
    *
    * Encodes `req` with [[JsonCodec]], POSTs to `http://localhost:port/method`, and decodes the response.
    */
  protected def invokeMethod[Req: JsonCodec, Resp: JsonCodec](port: Int, method: String, req: Req): Resp =
    val reqJson = summon[JsonCodec[Req]].encode(req)
    JsonCodec.decodeOrThrow[Resp](httpPost(s"http://localhost:$port/$method", reqJson))

  /** Deliver a CloudEvent to a [[dapr4s.Subscription]] route on a running [[dapr4s.internal.DaprAppServer]].
    *
    * Encodes `data` as the CloudEvent `data` field (embedded as a raw JSON value), POSTs to
    * `http://localhost:port/topic`, and returns the raw response body (typically `{"status":"SUCCESS"}`).
    */
  protected def deliverCloudEvent[T: JsonCodec](
      port: Int,
      topic: String,
      pubsubName: String,
      data: T,
      eventId: String = "test-event-id",
  ): String =
    val dataJson = summon[JsonCodec[T]].encode(data)
    val body =
      s"""{"id":"$eventId","source":"test","specversion":"1.0","type":"test.event",""" +
        s""""topic":"$topic","pubsubname":"$pubsubName","datacontenttype":"application/json","data":$dataJson}"""
    httpPost(s"http://localhost:$port/$topic", body)

  protected def httpPost(url: String, body: String, contentType: String = "application/json"): String =
    httpPostWithCode(url, body, contentType)._2

  protected def httpPostWithCode(
      url: String,
      body: String,
      contentType: String = "application/json",
  ): (Int, String) =
    val conn = java.net.URI(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
    conn.setRequestMethod("POST")
    conn.setRequestProperty("Content-Type", contentType)
    conn.setDoOutput(true)
    val bytes = body.getBytes("UTF-8").nn
    conn.setFixedLengthStreamingMode(bytes.length)
    conn.connect()
    conn.getOutputStream.nn.write(bytes)
    conn.getOutputStream.nn.flush()
    val code = conn.getResponseCode
    val stream =
      val err = conn.getErrorStream
      if err != null then err
      else if code < 400 then conn.getInputStream
      else null
    val resp = if stream == null then "" else new String(stream.nn.readAllBytes().nn, "UTF-8")
    (code, resp)
