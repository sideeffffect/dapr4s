package dapr.safe.test.unit

import dapr.safe.*
import dapr.safe.internal.DaprAppServer
import dapr.safe.test.integration.apps.*
import munit.FunSuite
import unsafeExceptions.canThrowAny
import java.util.concurrent.ConcurrentHashMap

/** Tests capability usage through real DaprAppServer HTTP dispatch.
  *
  * Uses MockDaprCapability as the backend but exercises the full HTTP → DaprAppServer → handler → capability call
  * chain.
  */
@scala.caps.assumeSafe
class CapabilityHandlerTest extends FunSuite:

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

  // -------------------------------------------------------------------------
  // State capability through server dispatch
  // -------------------------------------------------------------------------

  test("unit: state save+get through DaprAppServer handler"):
    val mock = MockDaprCapability()
    given DaprCapability = mock
    given StateCapability = mock.state(StoreName("store"))
    val app = DaprApp(
      invocations = List(
        InvocationRoute[String, String](MethodName("save-item")) { v =>
          try
            StateCapability.save(StateKey("item"), v)
            "saved"
          catch case e: Exception => throw e
        },
        InvocationRoute[Unit, Option[String]](MethodName("get-item")) { _ =>
          try StateCapability.get[String](StateKey("item"))
          catch case e: Exception => throw e
        },
      ),
    )
    val server = new DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      httpPost(s"http://localhost:$port/save-item", "\"hello\"", "application/json")
      val resp = httpPost(s"http://localhost:$port/get-item", "null", "application/json")
      assert(resp.contains("hello"), s"Expected hello in response, got: $resp")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: state missing key returns null through DaprAppServer handler"):
    val mock = MockDaprCapability()
    given DaprCapability = mock
    given StateCapability = mock.state(StoreName("store"))
    val app = DaprApp(
      invocations = List(
        InvocationRoute[Unit, Option[String]](MethodName("get-missing")) { _ =>
          try StateCapability.get[String](StateKey("no-such-key"))
          catch case e: Exception => throw e
        },
      ),
    )
    val server = new DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val resp = httpPost(s"http://localhost:$port/get-missing", "null", "application/json")
      assert(resp == "null", s"Expected null for missing key, got: $resp")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: state overwrite returns updated value through DaprAppServer handler"):
    val mock = MockDaprCapability()
    given DaprCapability = mock
    given StateCapability = mock.state(StoreName("store"))
    val app = DaprApp(
      invocations = List(
        InvocationRoute[String, String](MethodName("upsert")) { v =>
          try
            StateCapability.save(StateKey("k"), v)
            StateCapability.get[String](StateKey("k")).getOrElse("none")
          catch case e: Exception => throw e
        },
      ),
    )
    val server = new DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      httpPost(s"http://localhost:$port/upsert", "\"first\"", "application/json")
      val resp = httpPost(s"http://localhost:$port/upsert", "\"second\"", "application/json")
      assert(resp.contains("second"), s"Expected second, got: $resp")
    finally
      thread.interrupt()
      thread.join(2000)

  // -------------------------------------------------------------------------
  // PubSub through subscription handler
  // -------------------------------------------------------------------------

  test("unit: subscription handler can publish via PubSubCapability"):
    val mock = MockDaprCapability()
    given DaprCapability = mock
    given PubSubCapability = mock.pubsub(PubSubName("ps"))
    val app = DaprApp(
      subscriptions = List(
        Subscription[String](PubSubName("ps"), Topic("in-topic")) { event =>
          try
            PubSubCapability.publish(Topic("out-topic"), event.data + "-processed")
            SubscriptionResult.Success
          catch case e: Exception => throw e
        },
      ),
    )
    val server = new DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val cloudEvent =
        """{"specversion":"1.0","type":"x","source":"x","id":"1","topic":"in-topic",""" +
          """"pubsubname":"ps","datacontenttype":"application/json","data":"msg"}"""
      httpPost(s"http://localhost:$port/in-topic", cloudEvent, "application/json")
      assertEquals(mock.publishedEvents.length, 1)
      val (_, topic, payload, _) = mock.publishedEvents.head
      assertEquals(topic, "out-topic")
      assert(payload.contains("msg-processed"), s"Expected msg-processed in payload: $payload")
    finally
      thread.interrupt()
      thread.join(2000)

  // -------------------------------------------------------------------------
  // Closed scope through server
  // -------------------------------------------------------------------------

  test("unit: handler using closed capability returns 500"):
    val mock = MockDaprCapability()
    given DaprCapability = mock
    given StateCapability = mock.state(StoreName("store"))
    val app = DaprApp(
      invocations = List(
        InvocationRoute[Unit, Option[String]](MethodName("get-after-close")) { _ =>
          try StateCapability.get[String](StateKey("k"))
          catch case e: Exception => throw e
        },
      ),
    )
    mock.close() // close before server processes any request
    val server = new DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val (code, _) = httpPostWithCode(s"http://localhost:$port/get-after-close", "null", "application/json")
      assertEquals(code, 500)
    finally
      thread.interrupt()
      thread.join(2000)

  // -------------------------------------------------------------------------
  // Actor dispatch through HTTP
  // -------------------------------------------------------------------------

  test("unit: actor method dispatch through DaprAppServer HTTP"):
    // WHY AnyRef: MockActorContext extends ExclusiveCapability; use AnyRef map to avoid CC tracking.
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
      val resp1 = httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":5}""",
        "application/json",
      )
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp1).count, 5)
      val resp2 = httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":3}""",
        "application/json",
      )
      assertEquals(JsonCodec.decodeOrThrow[CounterState](resp2).count, 8)
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: actor unknown actor type returns 404"):
    val server = new DaprAppServer(CounterActorHandlers.daprApp)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val (code, _) = httpPostWithCode(
        s"http://localhost:$port/actors/Nonexistent/1/method/get",
        "null",
        "application/json",
      )
      assertEquals(code, 404)
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: actor unknown method returns 404"):
    val server = new DaprAppServer(CounterActorHandlers.daprApp)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val (code, _) = httpPostWithCode(
        s"http://localhost:$port/actors/Counter/1/method/no-such",
        "null",
        "application/json",
      )
      assertEquals(code, 404)
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: actor reminder dispatch through DaprAppServer HTTP"):
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
      // Seed state by incrementing first
      httpPost(
        s"http://localhost:$port/actors/Counter/actor-1/method/increment",
        """{"amount":77}""",
        "application/json",
      )
      // Deliver reminder with base64-encoded "reset" string payload
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
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: actor timer dispatch through DaprAppServer HTTP"):
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
    finally
      thread.interrupt()
      thread.join(2000)

  // -------------------------------------------------------------------------
  // WorkflowContext structural check
  // -------------------------------------------------------------------------

  test("unit: WorkflowApp DaprApp has non-empty workflows and activities"):
    val app = WorkflowApp.daprApp
    assert(app.workflows.nonEmpty, "expected non-empty workflows")
    assert(app.activities.nonEmpty, "expected non-empty activities")
