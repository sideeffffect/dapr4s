package dapr.safe.test.unit

import dapr.safe.*
import munit.FunSuite
import unsafeExceptions.canThrowAny

/** Unit tests for the subscriber-side types and DaprAppServer implementation.
  *
  * These tests verify the HTTP dispatch logic of DaprAppServer without requiring Docker — by calling the server's
  * internal helpers directly via a real local HTTP server on an ephemeral port.
  */
@scala.caps.assumeSafe
class SubscriberTest extends FunSuite:

  // -------------------------------------------------------------------------
  // SubscriptionResult
  // -------------------------------------------------------------------------

  test("unit: SubscriptionResult.Success is distinct"):
    assert(SubscriptionResult.Success != SubscriptionResult.Retry)
    assert(SubscriptionResult.Success != SubscriptionResult.Drop)

  test("unit: SubscriptionResult.Retry is distinct"):
    assert(SubscriptionResult.Retry != SubscriptionResult.Drop)

  // -------------------------------------------------------------------------
  // CloudEvent
  // -------------------------------------------------------------------------

  test("unit: CloudEvent stores all fields"):
    val event = CloudEvent[String](
      id = CloudEventId("abc"),
      source = CloudEventSource("test"),
      specVersion = CloudEventSpecVersion("1.0"),
      eventType = CloudEventType("com.example.event"),
      topic = Topic("orders"),
      pubSubName = PubSubName("pubsub"),
      dataContentType = ContentType("application/json"),
      data = "hello",
    )
    assertEquals(event.id, CloudEventId("abc"))
    assertEquals(event.source, CloudEventSource("test"))
    assertEquals(event.specVersion, CloudEventSpecVersion("1.0"))
    assertEquals(event.eventType, CloudEventType("com.example.event"))
    assertEquals(event.topic, Topic("orders"))
    assertEquals(event.pubSubName, PubSubName("pubsub"))
    assertEquals(event.dataContentType, ContentType("application/json"))
    assertEquals(event.data, "hello")

  // -------------------------------------------------------------------------
  // DaprApp + DaprAppServer dispatch
  // -------------------------------------------------------------------------

  test("unit: DaprAppServer dispatches pub/sub message from DaprApp"):
    var received: String | Null = null
    val app = DaprApp(
      subscriptions = List(
        Subscription[String](PubSubName("ps"), Topic("orders")) { event =>
          received = event.data
          SubscriptionResult.Success
        },
      ),
    )
    val server = new dapr.safe.internal.DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))

    try
      waitForPort(port)

      // Check /dapr/subscribe
      val subResp = httpGet(s"http://localhost:$port/dapr/subscribe")
      assert(subResp.contains("orders"), s"subscribe list missing topic: $subResp")
      assert(subResp.contains("ps"), s"subscribe list missing pubsub: $subResp")

      // POST a CloudEvent to /orders
      val cloudEvent =
        """{"specversion":"1.0","type":"com.dapr.event.sent","source":"test",
          |"id":"1","topic":"orders","pubsubname":"ps",
          |"datacontenttype":"application/json","data":"hello-world"}""".stripMargin
      val resp = httpPost(s"http://localhost:$port/orders", cloudEvent, "application/json")
      assert(resp.contains("SUCCESS"), s"expected SUCCESS, got: $resp")
      assertEquals(received, "hello-world")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: DaprAppServer dispatch returns RETRY on handler exception"):
    val app = DaprApp(
      subscriptions = List(
        Subscription[String](PubSubName("ps"), Topic("boom")) { _ =>
          throw RuntimeException("deliberate failure")
        },
      ),
    )
    val server = new dapr.safe.internal.DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val cloudEvent =
        """{"specversion":"1.0","type":"x","source":"x","id":"1",
          |"topic":"boom","pubsubname":"ps",
          |"datacontenttype":"application/json","data":"data"}""".stripMargin
      val resp = httpPost(s"http://localhost:$port/boom", cloudEvent, "application/json")
      assert(resp.contains("RETRY"), s"expected RETRY, got: $resp")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: DaprAppServer dispatch DROP on undecodable payload"):
    val app = DaprApp(
      subscriptions = List(
        Subscription[Int](PubSubName("ps"), Topic("numbers")) { _ => SubscriptionResult.Success },
      ),
    )
    val server = new dapr.safe.internal.DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      // "not-a-number" can't be decoded as Int → DROP
      val cloudEvent =
        """{"specversion":"1.0","type":"x","source":"x","id":"1",
          |"topic":"numbers","pubsubname":"ps",
          |"datacontenttype":"application/json","data":"not-a-number"}""".stripMargin
      val resp = httpPost(s"http://localhost:$port/numbers", cloudEvent, "application/json")
      assert(resp.contains("DROP"), s"expected DROP, got: $resp")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: DaprAppServer dispatches binding event from DaprApp"):
    var received: String | Null = null
    val app = DaprApp(
      bindings = List(
        BindingRoute[String](BindingName("myqueue")) { payload => received = payload },
      ),
    )
    val server = new dapr.safe.internal.DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      httpPost(s"http://localhost:$port/myqueue", "\"hello-binding\"", "application/json")
      assertEquals(received, "hello-binding")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: DaprAppServer dispatches invocation and returns response from DaprApp"):
    val app = DaprApp(
      invocations = List(
        InvocationRoute[String, String](MethodName("echo")) { req => "echo:" + req },
      ),
    )
    val server = new dapr.safe.internal.DaprAppServer(app)
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val resp = httpPost(s"http://localhost:$port/echo", "\"ping\"", "application/json")
      assert(resp.contains("echo:ping"), s"expected echo response, got: $resp")
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: DaprAppServer returns 404 for unknown route"):
    val server = new dapr.safe.internal.DaprAppServer(DaprApp())
    val port = freePort()
    val thread = Thread.ofVirtual().start(() => server.startAndBlock(port))
    try
      waitForPort(port)
      val (code, _) = httpPostWithCode(s"http://localhost:$port/unknown", "{}", "application/json")
      assertEquals(code, 404)
    finally
      thread.interrupt()
      thread.join(2000)

  test("unit: DaprApp ++ merges subscriptions and invocations"):
    val app1 = DaprApp(
      subscriptions = List(Subscription[String](PubSubName("p"), Topic("t1")) { _ => SubscriptionResult.Success }),
      invocations = List(InvocationRoute[String, String](MethodName("m1")) { s => s }),
    )
    val app2 = DaprApp(
      invocations = List(InvocationRoute[Int, Int](MethodName("m2")) { n => n + 1 }),
    )
    val combined = app1 ++ app2
    assertEquals(combined.subscriptions.size, 1)
    assertEquals(combined.invocations.size, 2)
    assert(combined.invocations.exists(_.methodName.value == "m1"))
    assert(combined.invocations.exists(_.methodName.value == "m2"))

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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
      catch
        case _: java.io.IOException =>
          Thread.sleep(20)
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
    // getInputStream throws FileNotFoundException on 4xx/5xx; use getErrorStream instead
    val stream =
      val err = conn.getErrorStream
      if err != null then err
      else if code < 400 then conn.getInputStream
      else null
    val resp = if stream == null then "" else new String(stream.readAllBytes().nn, "UTF-8")
    (code, resp)
