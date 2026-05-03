package dapr.safe.internal

import dapr.safe.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.dapr.workflows.runtime.WorkflowRuntimeBuilder
import java.net.InetSocketAddress
import java.util.{ArrayList, HashMap as JHashMap}
import scala.jdk.CollectionConverters.*
import unsafeExceptions.canThrowAny
import scala.util.control.NonFatal

/** HTTP server that serves Dapr subscriber protocol from a [[DaprApp]] description.
  *
  * The [[DaprApp]] is provided at construction time. Dispatch tables are built from it lazily inside [[startAndBlock]];
  * until then the object is immutable.
  *
  * Uses `com.sun.net.httpserver.HttpServer` (built into OpenJDK, in module `jdk.httpserver`) with a virtual-thread
  * executor, so each request runs on its own virtual thread with no additional dependencies.
  *
  * Java collections are used for the internal dispatch tables to keep the stored closures outside the Scala CC tracking
  * graph.
  */
@scala.caps.assumeSafe
private[safe] final class DaprAppServer(app: DaprApp):

  def startAndBlock(port: Int): Unit =

    // -----------------------------------------------------------------------
    // Build dispatch tables from DaprApp
    // -----------------------------------------------------------------------

    // For /dapr/subscribe response — ordered list of (pubsubName, topic, route) triples
    val pubSubEntries: ArrayList[Array[String]] = ArrayList()

    // Path → handler, stored as AnyRef to keep Java collections outside CC.
    val pubSubRoutes: JHashMap[String, AnyRef] = JHashMap()
    val bindingRoutes: JHashMap[String, AnyRef] = JHashMap()
    val invokeRoutes: JHashMap[String, AnyRef] = JHashMap()

    // actorType → actorDefinition
    val actorDefs: JHashMap[String, ActorDefinition] = JHashMap()

    for sub <- app.subscriptions do
      val path = if sub.route.value.startsWith("/") then sub.route.value else "/" + sub.route.value
      val handler = sub.rawHandler.asInstanceOf[CloudEvent[sub.Payload] => SubscriptionResult]
      pubSubEntries.add(Array(sub.pubsubName.value, sub.topic.value, path))
      val fn: String => SubscriptionResult = bodyJson =>
        parseCloudEvent(bodyJson, sub.codec, sub.pubsubName, sub.topic, handler)
      pubSubRoutes.put(path, fn.asInstanceOf[AnyRef])

    for inv <- app.invocations do
      val path = "/" + inv.methodName.value
      val handler = inv.rawHandler.asInstanceOf[inv.Req => inv.Resp]
      val fn: String => String = bodyJson =>
        inv.reqCodec.decode(if bodyJson.isEmpty then "null" else bodyJson) match
          case Right(req) => inv.respCodec.encode(handler(req))
          case Left(e)    =>
            throw RuntimeException(
              s"Cannot decode invocation request for '${inv.methodName.value}': ${e.getMessage}",
              e,
            )
      invokeRoutes.put(path, fn.asInstanceOf[AnyRef])

    for bin <- app.bindings do
      val path = "/" + bin.bindingName.value
      val handler = bin.rawHandler.asInstanceOf[bin.Payload => Unit]
      val fn: String => Unit = bodyJson =>
        bin.codec.decode(bodyJson) match
          case Right(data) => handler(data)
          case Left(e)     =>
            throw RuntimeException(
              s"Cannot decode binding payload for '${bin.bindingName.value}': ${e.getMessage}",
              e,
            )
      bindingRoutes.put(path, fn.asInstanceOf[AnyRef])

    for actorDef <- app.actors do actorDefs.put(actorDef.actorType.value, actorDef)

    // -----------------------------------------------------------------------
    // Workflow/activity runtime (created only if needed)
    // -----------------------------------------------------------------------

    val workflowRuntime =
      if app.workflows.nonEmpty || app.activities.nonEmpty then
        val wb = new WorkflowRuntimeBuilder()
        // WHY named registration: DaprWorkflowBridge is one class wrapping many user workflows.
        // We register each under the user workflow's canonical class name so that
        // WorkflowCapability.start(WorkflowName(classOf[MyWorkflow].getCanonicalName)) resolves correctly.
        app.workflows.foreach { w =>
          wb.registerWorkflow(w.getClass.getCanonicalName.nn, new DaprWorkflowBridge(w), "", false)
        }
        app.activities.foreach { a =>
          wb.registerActivity(a.getClass.getCanonicalName.nn, new DaprActivityBridge(a))
        }
        val rt = wb.build()
        rt.start(false)
        rt
      else null

    // -----------------------------------------------------------------------
    // Dapr HTTP port (for actor state API calls back to sidecar)
    // -----------------------------------------------------------------------

    val daprHttpPort: Int =
      Option(System.getenv("DAPR_HTTP_PORT")).flatMap(_.toIntOption).getOrElse(3500)

    // -----------------------------------------------------------------------
    // HTTP server
    // -----------------------------------------------------------------------

    val server = HttpServer.create(new InetSocketAddress(port), /*backlog=*/ 0)
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())

    // Dapr sidecar calls GET /dapr/subscribe to discover pub/sub subscriptions.
    server.createContext(
      "/dapr/subscribe",
      exchange =>
        try
          if exchange.getRequestMethod.nn == "GET" then
            val arr = ujson.Arr.from(
              pubSubEntries.asScala.map(e => ujson.Obj("pubsubname" -> e(0), "topic" -> e(1), "route" -> e(2))),
            )
            sendJson(exchange, 200, ujson.write(arr))
          else
            exchange.sendResponseHeaders(405, -1)
            exchange.getResponseBody.nn.close()
        catch
          case NonFatal(_) =>
            try
              exchange.sendResponseHeaders(500, -1)
              exchange.getResponseBody.nn.close()
            catch case NonFatal(_) => (),
    )

    // Dapr sidecar calls GET /dapr/config to discover hosted actor types.
    server.createContext(
      "/dapr/config",
      exchange =>
        try
          if exchange.getRequestMethod.nn == "GET" then
            val types = actorDefs.keySet().asScala.toList.sorted
            val json = ujson.write(
              ujson.Obj(
                "entities" -> ujson.Arr.from(types.map(ujson.Str(_))),
                "actorIdleTimeout" -> "1h",
                "actorScanInterval" -> "30s",
                "drainOngoingCallTimeout" -> "30s",
                "drainRebalancedActors" -> true,
              ),
            )
            sendJson(exchange, 200, json)
          else
            exchange.sendResponseHeaders(405, -1)
            exchange.getResponseBody.nn.close()
        catch
          case NonFatal(_) =>
            try
              exchange.sendResponseHeaders(500, -1)
              exchange.getResponseBody.nn.close()
            catch case NonFatal(_) => (),
    )

    // Actor routes: /actors/{type}/{id}/method/{name}
    //               /actors/{type}/{id}/method/remind/{name}
    //               /actors/{type}/{id}/method/timer/{name}
    //               DELETE /actors/{type}/{id}
    if actorDefs.size() > 0 then
      server.createContext(
        "/actors",
        exchange =>
          val path = exchange.getRequestURI.nn.getPath.nn
          try handleActorRequest(exchange, path, actorDefs, daprHttpPort)
          catch
            case NonFatal(_) =>
              try
                exchange.sendResponseHeaders(500, -1)
                exchange.getResponseBody.nn.close()
              catch case NonFatal(_) => (),
      )

    // Catch-all: pub/sub delivery, input bindings, service invocation.
    // HttpServer uses longest-prefix matching, so /dapr/subscribe and /dapr/config above win
    // for those exact paths; "/" handles everything else.
    server.createContext(
      "/",
      exchange =>
        val path = exchange.getRequestURI.nn.getPath.nn
        try
          val psFn = pubSubRoutes.get(path)
          if psFn != null then
            val fn = psFn.asInstanceOf[String => SubscriptionResult]
            val body = readBody(exchange)
            val result =
              try fn(body)
              catch case NonFatal(_) => SubscriptionResult.Retry
            val status = result match
              case SubscriptionResult.Success => "SUCCESS"
              case SubscriptionResult.Retry   => "RETRY"
              case SubscriptionResult.Drop    => "DROP"
            sendJson(exchange, 200, s"""{"status":"$status"}""")
          else
            val bnFn = bindingRoutes.get(path)
            if bnFn != null then
              val fn = bnFn.asInstanceOf[String => Unit]
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
                val fn = ivFn.asInstanceOf[String => String]
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
            catch case NonFatal(_) => (),
    )

    server.start()

    // Register a JVM shutdown hook so the server drains in-flight requests
    // on SIGTERM/SIGINT before the JVM exits.
    Runtime.getRuntime.addShutdownHook(
      Thread
        .ofVirtual()
        .unstarted(() =>
          server.stop( /*seconds grace=*/ 2)
          if workflowRuntime != null then workflowRuntime.close(),
        ),
    )

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
  // Actor request dispatch
  // -------------------------------------------------------------------------

  private def handleActorRequest(
      exchange: HttpExchange,
      path: String,
      actorDefs: JHashMap[String, ActorDefinition],
      daprHttpPort: Int,
  ): Unit =
    // Path patterns (after stripping leading /actors/):
    //   {type}/{id}/method/{name}              — method invocation
    //   {type}/{id}/method/remind/{name}       — reminder callback
    //   {type}/{id}/method/timer/{name}        — timer callback
    //   DELETE {type}/{id}                     — deactivation (return 200)
    val parts = path.stripPrefix("/actors/").split("/", -1)
    parts match
      case Array(actorType, actorId, "method", methodName) if methodName != "remind" && methodName != "timer" =>
        dispatchActorMethod(exchange, actorType, actorId, methodName, actorDefs, daprHttpPort)

      case Array(actorType, actorId, "method", "remind", reminderName) =>
        dispatchActorReminder(exchange, actorType, actorId, reminderName, actorDefs, daprHttpPort)

      case Array(actorType, actorId, "method", "timer", timerName) =>
        dispatchActorTimer(exchange, actorType, actorId, timerName, actorDefs, daprHttpPort)

      case Array(actorType, actorId) if exchange.getRequestMethod.nn == "DELETE" =>
        // Actor deactivation — no cleanup needed in our model
        exchange.sendResponseHeaders(200, -1)
        exchange.getResponseBody.nn.close()

      case _ =>
        exchange.sendResponseHeaders(404, -1)
        exchange.getResponseBody.nn.close()

  private def dispatchActorMethod(
      exchange: HttpExchange,
      actorType: String,
      actorId: String,
      methodName: String,
      actorDefs: JHashMap[String, ActorDefinition],
      daprHttpPort: Int,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then
      exchange.sendResponseHeaders(404, -1)
      exchange.getResponseBody.nn.close()
    else
      val ctx = new HttpActorContext(ActorType(actorType), ActorId(actorId), daprHttpPort)
      val routes = defn.build(ActorId(actorId), ctx)
      val route = routes.methods.find(_.methodName.value == methodName).orNull
      if route == null then
        exchange.sendResponseHeaders(404, -1)
        exchange.getResponseBody.nn.close()
      else
        val body = readBody(exchange)
        val handler = route.rawHandler.asInstanceOf[route.Req => route.Resp]
        route.reqCodec.decode(if body.isEmpty then "null" else body) match
          case Left(_) =>
            exchange.sendResponseHeaders(400, -1)
            exchange.getResponseBody.nn.close()
          case Right(req) =>
            val resp = handler(req)
            sendJson(exchange, 200, route.respCodec.encode(resp))

  private def dispatchActorReminder(
      exchange: HttpExchange,
      actorType: String,
      actorId: String,
      reminderName: String,
      actorDefs: JHashMap[String, ActorDefinition],
      daprHttpPort: Int,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then
      exchange.sendResponseHeaders(404, -1)
      exchange.getResponseBody.nn.close()
    else
      val ctx = new HttpActorContext(ActorType(actorType), ActorId(actorId), daprHttpPort)
      val routes = defn.build(ActorId(actorId), ctx)
      val route = routes.reminders.find(_.reminderName.value == reminderName).orNull
      if route == null then
        // Reminder delivered but no handler registered — acknowledge it silently
        exchange.sendResponseHeaders(200, -1)
        exchange.getResponseBody.nn.close()
      else
        val body = readBody(exchange)
        val handler = route.rawHandler.asInstanceOf[route.Payload => Unit]
        val payload = decodeCallbackPayload(body, route.codec)
        handler(payload)
        exchange.sendResponseHeaders(200, -1)
        exchange.getResponseBody.nn.close()

  private def dispatchActorTimer(
      exchange: HttpExchange,
      actorType: String,
      actorId: String,
      timerName: String,
      actorDefs: JHashMap[String, ActorDefinition],
      daprHttpPort: Int,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then
      exchange.sendResponseHeaders(404, -1)
      exchange.getResponseBody.nn.close()
    else
      val ctx = new HttpActorContext(ActorType(actorType), ActorId(actorId), daprHttpPort)
      val routes = defn.build(ActorId(actorId), ctx)
      val route = routes.timers.find(_.timerName.value == timerName).orNull
      if route == null then
        exchange.sendResponseHeaders(200, -1)
        exchange.getResponseBody.nn.close()
      else
        val body = readBody(exchange)
        val handler = route.rawHandler.asInstanceOf[route.Payload => Unit]
        val payload = decodeCallbackPayload(body, route.codec)
        handler(payload)
        exchange.sendResponseHeaders(200, -1)
        exchange.getResponseBody.nn.close()

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

  /** Decode the `data` field from a reminder/timer callback body.
    *
    * The Dapr sidecar sends `{"data":"base64-encoded-json","dueTime":"...","period":"..."}`. We base64-decode the
    * `data` field and then JSON-decode it with the route's codec.
    */
  private def decodeCallbackPayload[T](body: String, codec: JsonCodec[T]): T =
    try
      val env = ujson.read(body).obj
      val data = env.get("data").map(_.str).getOrElse("")
      val json =
        if data.isEmpty then "null"
        else new String(java.util.Base64.getDecoder.nn.decode(data).nn, "UTF-8")
      codec.decode(json).getOrElse(throw RuntimeException("Failed to decode callback payload"))
    catch
      case e: RuntimeException            => throw e
      case scala.util.control.NonFatal(e) =>
        throw RuntimeException("Failed to parse callback body", e)

  private def parseCloudEvent[T](
      bodyJson: String,
      codec: JsonCodec[T],
      defaultPubsubName: PubSubName,
      defaultTopic: Topic,
      handler: CloudEvent[T] => SubscriptionResult,
  ): SubscriptionResult =
    try
      val env = ujson.read(bodyJson).obj
      val data = env.get("data").map(v => ujson.write(v)).getOrElse("null")
      codec.decode(data) match
        case Left(_)  => SubscriptionResult.Drop
        case Right(v) =>
          handler(
            CloudEvent[T](
              id = env.get("id").map(_.str).getOrElse(""),
              source = env.get("source").map(_.str).getOrElse(""),
              specVersion = env.get("specversion").map(_.str).getOrElse("1.0"),
              eventType = env.get("type").map(_.str).getOrElse(""),
              topic = env.get("topic").map(s => Topic(s.str)).getOrElse(defaultTopic),
              pubSubName = env.get("pubsubname").map(s => PubSubName(s.str)).getOrElse(defaultPubsubName),
              dataContentType = env.get("datacontenttype").map(_.str).getOrElse("application/json"),
              data = v,
            ),
          )
    catch case NonFatal(_) => SubscriptionResult.Retry
