package dapr.safe.internal

import dapr.safe.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.util.concurrent.{CopyOnWriteArrayList, Executors}
import java.util.{ArrayList, HashMap as JHashMap}
import scala.jdk.CollectionConverters.*
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny
import scala.util.control.NonFatal

/** HTTP server that implements [[AppHandlers]] for the Dapr subscriber
  * protocol.  Registration happens before `startAndBlock`; once the server
  * is running the internal tables are read-only.
  *
  * Uses `com.sun.net.httpserver.HttpServer` (built into OpenJDK, in module
  * `jdk.httpserver`) with a virtual-thread executor, so each request runs on
  * its own virtual thread with no additional dependencies.
  *
  * Java collections are used for the internal dispatch tables to keep the
  * stored closures outside the Scala CC tracking graph.
  */
@scala.caps.assumeSafe
private[safe] final class DaprAppServer extends AppHandlers:

  // -------------------------------------------------------------------------
  // Internal registration tables (written only during setup, before start)
  // -------------------------------------------------------------------------

  // For /dapr/subscribe response — ordered list of (pubsubName, topic, route) triples
  private val pubSubEntries: ArrayList[Array[String]] = ArrayList()

  // Path → handler, stored as AnyRef to keep Java collections outside CC.
  // Actual value types: pubSubRoutes: String=>SubscriptionResult, etc.
  private val pubSubRoutes : JHashMap[String, AnyRef] = JHashMap()
  private val bindingRoutes: JHashMap[String, AnyRef] = JHashMap()
  private val invokeRoutes : JHashMap[String, AnyRef] = JHashMap()

  // -------------------------------------------------------------------------
  // AppHandlers implementation
  // -------------------------------------------------------------------------

  def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Unit =
    subscribe(pubsubName, topic, "/" + topic.value)(handler)

  def subscribe[T: JsonCodec](pubsubName: PubSubName, topic: Topic, route: String)(
    handler: CloudEvent[T] => SubscriptionResult
  ): Unit =
    val path  = if route.startsWith("/") then route else "/" + route
    val codec = summon[JsonCodec[T]]
    pubSubEntries.add(Array(pubsubName.value, topic.value, path))
    val fn: String => SubscriptionResult = bodyJson =>
      parseCloudEvent(bodyJson, codec, pubsubName.value, topic.value, handler)
    pubSubRoutes.put(path, fn.asInstanceOf[AnyRef])

  def onBinding[T: JsonCodec](bindingName: BindingName)(handler: T => Unit): Unit =
    val codec = summon[JsonCodec[T]]
    val path  = "/" + bindingName.value
    val fn: String => Unit = bodyJson =>
      codec.decode(bodyJson) match
        case Right(data) => handler(data)
        case Left(e)     =>
          throw DaprAppServerException(
            s"Cannot decode binding payload for '${bindingName.value}': ${e.getMessage}", e
          )
    bindingRoutes.put(path, fn.asInstanceOf[AnyRef])

  def onInvoke[Req: JsonCodec](methodName: String)[Resp: JsonCodec](
    handler: Req => Resp
  ): Unit =
    val reqCodec  = summon[JsonCodec[Req]]
    val respCodec = summon[JsonCodec[Resp]]
    val path = "/" + methodName
    val fn: String => String = bodyJson =>
      reqCodec.decode(if bodyJson.isEmpty then "null" else bodyJson) match
        case Right(req) => respCodec.encode(handler(req))
        case Left(e)    =>
          throw DaprAppServerException(
            s"Cannot decode invocation request for '$methodName': ${e.getMessage}", e
          )
    invokeRoutes.put(path, fn.asInstanceOf[AnyRef])

  // -------------------------------------------------------------------------
  // Server lifecycle
  // -------------------------------------------------------------------------

  def startAndBlock(port: Int): Unit =
    val server = HttpServer.create(new InetSocketAddress(port), /*backlog=*/ 0)
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())

    // Dapr sidecar calls GET /dapr/subscribe to discover pub/sub subscriptions.
    server.createContext(
      "/dapr/subscribe",
      exchange =>
        try
          if exchange.getRequestMethod.nn == "GET" then
            val arr = ujson.Arr.from(pubSubEntries.asScala.map(e =>
              ujson.Obj("pubsubname" -> e(0), "topic" -> e(1), "route" -> e(2))
            ))
            sendJson(exchange, 200, ujson.write(arr))
          else
            exchange.sendResponseHeaders(405, -1)
            exchange.getResponseBody.nn.close()
        catch
          case NonFatal(_) =>
            try
              exchange.sendResponseHeaders(500, -1)
              exchange.getResponseBody.nn.close()
            catch case NonFatal(_) => ()
    )

    // Catch-all: pub/sub delivery, input bindings, service invocation.
    // HttpServer uses longest-prefix matching, so /dapr/subscribe above wins
    // for that exact path; "/" handles everything else.
    server.createContext(
      "/",
      exchange =>
        val path = exchange.getRequestURI.nn.getPath.nn
        try
          val psFn = pubSubRoutes.get(path)
          if psFn != null then
            val fn = psFn.asInstanceOf[String => SubscriptionResult]
            val body   = readBody(exchange)
            val result = try fn(body) catch case NonFatal(_) => SubscriptionResult.Retry
            val status = result match
              case SubscriptionResult.Success => "SUCCESS"
              case SubscriptionResult.Retry   => "RETRY"
              case SubscriptionResult.Drop    => "DROP"
            sendJson(exchange, 200, s"""{"status":"$status"}""")
          else
            val bnFn = bindingRoutes.get(path)
            if bnFn != null then
              val fn   = bnFn.asInstanceOf[String => Unit]
              val body = readBody(exchange)
              try
                fn(body)
                exchange.sendResponseHeaders(200, -1)
                exchange.getResponseBody.nn.close()
              catch
                case NonFatal(_) =>
                  exchange.sendResponseHeaders(500, -1)
                  exchange.getResponseBody.nn.close()
            else
              val ivFn = invokeRoutes.get(path)
              if ivFn != null then
                val fn   = ivFn.asInstanceOf[String => String]
                val body = readBody(exchange)
                val resp = fn(body)
                sendJson(exchange, 200, resp)
              else
                exchange.sendResponseHeaders(404, -1)
                exchange.getResponseBody.nn.close()
        catch
          case NonFatal(_) =>
            try
              exchange.sendResponseHeaders(500, -1)
              exchange.getResponseBody.nn.close()
            catch case NonFatal(_) => ()
    )

    server.start()

    // Register a JVM shutdown hook so the server drains in-flight requests
    // on SIGTERM/SIGINT before the JVM exits.
    Runtime.getRuntime.addShutdownHook(Thread.ofVirtual().unstarted(() =>
      server.stop(/*seconds grace=*/ 2)
    ))

    // Block the calling thread until it is interrupted (e.g. by a test or
    // by the shutdown hook completing).  Restore the interrupt flag so callers
    // can detect it after serve() returns.
    try Thread.currentThread().join()
    catch
      case e: InterruptedException =>
        // WHY WE CATCH InterruptedException HERE
        // Same contract as MonoOps.awaitResult: Thread.join() clears the
        // interrupt flag when it throws InterruptedException.  We restore it
        // immediately and then stop the server cleanly.
        Thread.currentThread().interrupt()
        server.stop(2)

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private def readBody(exchange: HttpExchange): String =
    new String(exchange.getRequestBody.nn.readAllBytes().nn, "UTF-8")

  private def sendJson(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes("UTF-8").nn
    exchange.getResponseHeaders.nn.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    val out = exchange.getResponseBody.nn
    out.write(bytes)
    out.close()

  private def parseCloudEvent[T](
    bodyJson: String,
    codec: JsonCodec[T],
    defaultPubsubName: String,
    defaultTopic: String,
    handler: CloudEvent[T] => SubscriptionResult
  ): SubscriptionResult =
    try
      val env  = ujson.read(bodyJson).obj
      val data = env.get("data").map(v => ujson.write(v)).getOrElse("null")
      codec.decode(data) match
        case Left(_)  => SubscriptionResult.Drop
        case Right(v) =>
          handler(
            CloudEvent[T](
              id              = env.get("id").map(_.str).getOrElse(""),
              source          = env.get("source").map(_.str).getOrElse(""),
              specVersion     = env.get("specversion").map(_.str).getOrElse("1.0"),
              eventType       = env.get("type").map(_.str).getOrElse(""),
              topic           = env.get("topic").map(_.str).getOrElse(defaultTopic),
              pubSubName      = env.get("pubsubname").map(_.str).getOrElse(defaultPubsubName),
              dataContentType = env.get("datacontenttype").map(_.str).getOrElse("application/json"),
              data            = v
            )
          )
    catch case NonFatal(_) => SubscriptionResult.Retry
