package dapr.safe.test.integration

import dapr.safe.*
import dapr.safe.internal.DaprAppServer
import dapr.safe.test.integration.apps.*
import dapr.safe.test.unit.MockActorContext
import munit.FunSuite
import java.util.concurrent.ConcurrentHashMap
import unsafeExceptions.canThrowAny

/** Tests for Counter actor dispatch via DaprAppServer HTTP.
  *
  * Uses an injectable MockActorContext factory so state accumulates correctly across calls to the same actor ID,
  * without requiring a real Dapr sidecar.
  */
@scala.caps.assumeSafe
class ActorIntegrationTest extends FunSuite:

  // Each test gets its own server on an ephemeral port with a fresh context map.
  // WHY AnyRef: MockActorContext extends ActorContext which extends ExclusiveCapability,
  // so CC tracks every instance. ConcurrentHashMap[String, AnyRef] erases the capture
  // set from the stored contexts, consistent with the @assumeSafe / AnyRef-erasure pattern.
  private def withActorServer[T](f: (Int, ConcurrentHashMap[String, AnyRef]) => T): T =
    val contexts = ConcurrentHashMap[String, AnyRef]()
    val server = DaprAppServer(
      CounterActorHandlers.daprApp,
      mkActorCtx = (_, id, _) =>
        contexts
          .computeIfAbsent(id.value, _ => new MockActorContext().asInstanceOf[AnyRef])
          .asInstanceOf[MockActorContext],
    )
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      f(port, contexts)
    finally
      thread.interrupt()
      thread.join(2000)

  // ---- method dispatch -------------------------------------------------------

  test("actor: increment from zero via HTTP"):
    withActorServer: (port, _) =>
      val resp = httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":5}""",
        "application/json",
      )
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 5)

  test("actor: increment accumulates via HTTP"):
    withActorServer: (port, _) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":3}""",
        "application/json",
      )
      val resp = httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":7}""",
        "application/json",
      )
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 10)

  test("actor: get returns 0 for fresh actor via HTTP"):
    withActorServer: (port, _) =>
      val resp =
        httpPost(s"http://localhost:$port/actors/Counter/actor-1/method/get", "null", "application/json")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)

  test("actor: reset brings count to zero via HTTP"):
    withActorServer: (port, _) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":100}""",
        "application/json",
      )
      val resp =
        httpPost(s"http://localhost:$port/actors/Counter/actor-1/method/reset", "null", "application/json")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)

  test("actor: get after reset returns 0 via HTTP"):
    withActorServer: (port, _) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":50}""",
        "application/json",
      )
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/reset",
        "null",
        "application/json",
      )
      val resp =
        httpPost(s"http://localhost:$port/actors/Counter/actor-1/method/get", "null", "application/json")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)

  test("actor: state isolation across different actor IDs via HTTP"):
    withActorServer: (port, _) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":10}""",
        "application/json",
      )
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-2/method/increment",
        """{"amount":20}""",
        "application/json",
      )
      val r1 =
        httpPost(s"http://localhost:$port/actors/Counter/actor-1/method/get", "null", "application/json")
      val r2 =
        httpPost(s"http://localhost:$port/actors/Counter/actor-2/method/get", "null", "application/json")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](r1).count, 10)
      assertEquals(JsonCodec.decodeOrThrow[CounterState](r2).count, 20)

  // ---- reminder/timer registration ------------------------------------------

  test("actor: schedule-reset registers reminder in mock context via HTTP"):
    withActorServer: (port, ctxs) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/schedule-reset",
        "null",
        "application/json",
      )
      val ctx = Option(ctxs.get("actor-1")).map(_.asInstanceOf[MockActorContext])
      assert(ctx.isDefined, "actor-1 context not found after call")
      assert(ctx.get.registeredReminders.contains("scheduled-reset"))
      assertEquals(ctx.get.registeredReminders("scheduled-reset")._2, java.time.Duration.ofMinutes(1))

  test("actor: cancel-reset removes reminder via HTTP"):
    withActorServer: (port, ctxs) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/schedule-reset",
        "null",
        "application/json",
      )
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/cancel-reset",
        "null",
        "application/json",
      )
      val ctx = Option(ctxs.get("actor-1")).map(_.asInstanceOf[MockActorContext])
      assert(ctx.isEmpty || !ctx.get.registeredReminders.contains("scheduled-reset"))

  test("actor: schedule-auto-increment registers timer via HTTP"):
    withActorServer: (port, ctxs) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/schedule-auto-increment",
        "null",
        "application/json",
      )
      val ctx = Option(ctxs.get("actor-1")).map(_.asInstanceOf[MockActorContext])
      assert(ctx.isDefined)
      assert(ctx.get.registeredTimers.contains("auto-increment"))
      assertEquals(ctx.get.registeredTimers("auto-increment")._2, java.time.Duration.ofMillis(500))

  // ---- reminder/timer callback dispatch -------------------------------------

  test("actor: reminder callback resets counter via HTTP"):
    withActorServer: (port, _) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":77}""",
        "application/json",
      )
      val dataB64 = java.util.Base64.getEncoder.nn.encodeToString("\"reset\"".getBytes("UTF-8"))
      val reminderBody = s"""{"data":"$dataB64","dueTime":"1h","period":""}"""
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/remind/scheduled-reset",
        reminderBody,
        "application/json",
      )
      val resp =
        httpPost(s"http://localhost:$port/actors/Counter/actor-1/method/get", "null", "application/json")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 0)

  test("actor: timer callback increments counter via HTTP"):
    withActorServer: (port, _) =>
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":10}""",
        "application/json",
      )
      val dataB64 =
        java.util.Base64.getEncoder.nn.encodeToString("""{"amount":1}""".getBytes("UTF-8"))
      val timerBody = s"""{"data":"$dataB64","dueTime":"500ms","period":""}"""
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/timer/auto-increment",
        timerBody,
        "application/json",
      )
      val resp =
        httpPost(s"http://localhost:$port/actors/Counter/actor-1/method/get", "null", "application/json")
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp).count, 11)

  // ---- error cases ----------------------------------------------------------

  test("actor: unknown actor type returns 404 via HTTP"):
    withActorServer: (port, _) =>
      val (code, _) = httpPostWithCode(
        s"http://localhost:$port/actors/NonExistent/1/method/get",
        "null",
        "application/json",
      )
      assertEquals(code, 404)

  test("actor: unknown method returns 404 via HTTP"):
    withActorServer: (port, _) =>
      val (code, _) = httpPostWithCode(
        s"http://localhost:$port/actors/Counter/1/method/no-such",
        "null",
        "application/json",
      )
      assertEquals(code, 404)

  test("actor: /dapr/config lists Counter actor type"):
    withActorServer: (port, _) =>
      val resp = httpGet(s"http://localhost:$port/dapr/config")
      assert(resp.contains("Counter"), s"Expected Counter in config response: $resp")

  test("actor: DELETE deactivation returns 200"):
    withActorServer: (port, _) =>
      val (code, _) = httpDeleteWithCode(s"http://localhost:$port/actors/Counter/actor-1")
      assertEquals(code, 200)

  test("actor: DaprApp ++ merges actor definitions"):
    val app1 = CounterActorHandlers.daprApp
    val app2 = DaprApp(actors = List(ActorDefinition(ActorType("Other")) { (_, _) => ActorRoutes() }))
    val combined = app1 ++ app2
    assertEquals(combined.actors.size, 2)
    assert(combined.actors.exists(_.actorType.value == "Counter"))
    assert(combined.actors.exists(_.actorType.value == "Other"))

  // ---- HTTP helpers ---------------------------------------------------------

  private def freePort(): Int =
    val sock = java.net.ServerSocket(0)
    val p = sock.getLocalPort
    sock.close()
    p

  private def waitForPort(port: Int, maxMs: Int = 3000): Unit =
    val deadline = System.currentTimeMillis() + maxMs
    while System.currentTimeMillis() < deadline do
      try
        val sock = java.net.Socket("localhost", port)
        sock.close()
        return
      catch case _: java.io.IOException => Thread.sleep(20)
    throw RuntimeException(s"Port $port did not open within ${maxMs}ms")

  private def httpGet(url: String): String =
    val conn = java.net.URI(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
    conn.setRequestMethod("GET")
    conn.connect()
    val code = conn.getResponseCode
    val stream = if code < 400 then conn.getInputStream.nn else conn.getErrorStream.nn
    new String(stream.readAllBytes().nn, "UTF-8")

  private def httpPost(url: String, body: String, contentType: String): String =
    httpPostWithCode(url, body, contentType)._2

  private def httpPostWithCode(url: String, body: String, contentType: String): (Int, String) =
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

  private def httpDeleteWithCode(url: String): (Int, String) =
    val conn = java.net.URI(url).toURL.nn.openConnection().nn.asInstanceOf[java.net.HttpURLConnection]
    conn.setRequestMethod("DELETE")
    conn.connect()
    val code = conn.getResponseCode
    val stream =
      val err = conn.getErrorStream
      if err != null then err
      else if code < 400 then conn.getInputStream
      else null
    val resp = if stream == null then "" else new String(stream.nn.readAllBytes().nn, "UTF-8")
    (code, resp)
