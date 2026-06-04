package dapr4s.internal

import dapr4s.*
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.dapr.workflows.runtime.WorkflowRuntimeBuilder
import java.net.{InetSocketAddress, URI}
import java.util.{ArrayList, HashMap as JHashMap}
import java.util.logging.{Level, Logger}
import scala.concurrent.duration.{FiniteDuration, DurationInt}
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
private[dapr4s] final class DaprAppServer(app: DaprApp):

  import DaprAppServer.*

  def startAndBlock(
      port: Int,
      daprCapability: DaprCapability,
      sidecarHttpEndpoint: () => URI = () => URI.create("http://localhost:3500"),
      workflowProperties: io.dapr.config.Properties = new io.dapr.config.Properties(),
      shutdownGrace: FiniteDuration = 2.seconds,
      httpBacklog: Int = 0,
      actorConfig: ActorRuntimeConfig = ActorRuntimeConfig(),
  ): Nothing =

    // -----------------------------------------------------------------------
    // Build dispatch tables from DaprApp
    // -----------------------------------------------------------------------

    // For /dapr/subscribe response — ordered list of (pubsubName, topic, route, deadLetterTopic)
    // entries; the 4th element is "" (empty) when no dead-letter topic is configured.
    val pubSubEntries: ArrayList[Array[String]] = ArrayList()

    // Path → handler, stored as AnyRef to keep Java collections outside CC.
    val pubSubRoutes: JHashMap[String, AnyRef] = JHashMap()
    val bindingRoutes: JHashMap[String, AnyRef] = JHashMap()
    val invokeRoutes: JHashMap[String, AnyRef] = JHashMap()
    val jobRoutes: JHashMap[String, AnyRef] = JHashMap()

    // actorType → actorDefinition
    val actorDefs: JHashMap[String, ActorDefinition] = JHashMap()

    for sub <- app.subscriptions do
      val path = if sub.route.value.startsWith("/") then sub.route.value else "/" + sub.route.value
      val handler = sub.rawHandler.asInstanceOf[CloudEvent[sub.Payload] => SubscriptionResult]
      pubSubEntries.add(
        Array(sub.pubsubName.value, sub.topic.value, path, sub.deadLetterTopic.map(_.value).getOrElse("")),
      )
      val fn: String => SubscriptionResult = bodyJson =>
        parseCloudEvent(bodyJson, sub.codec, sub.pubsubName, sub.topic, handler)
      pubSubRoutes.put(path, fn.asInstanceOf[AnyRef])

    for inv <- app.invocations do
      val path = "/" + inv.methodName.value
      val fn: (String, String) => String =
        if inv.usesRequestEnvelope then
          val handler = inv.rawHandler.asInstanceOf[InvocationRequest[inv.Req] => inv.Resp]
          (methodStr, bodyJson) =>
            inv.reqCodec.decode(if bodyJson.isEmpty then "null" else bodyJson) match
              case Right(req) =>
                inv.respCodec.encode(handler(InvocationRequest(inv.methodName, parseHttpMethod(methodStr), req)))
              case Left(e) =>
                throw RuntimeException(
                  s"Cannot decode invocation request for '${inv.methodName.value}': ${e.getMessage}",
                  e,
                )
        else
          val handler = inv.rawHandler.asInstanceOf[inv.Req => inv.Resp]
          (_, bodyJson) =>
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

    for job <- app.jobs do
      val path = "/job/" + job.name.value
      val handler = job.rawHandler.asInstanceOf[job.Payload => Unit]
      val fn: String => Unit = bodyJson =>
        decodeJobPayload(bodyJson, job.codec) match
          case Right(data) => handler(data)
          case Left(e)     =>
            throw RuntimeException(
              s"Cannot decode job payload for '${job.name.value}': ${e.getMessage}",
              e,
            )
      jobRoutes.put(path, fn.asInstanceOf[AnyRef])

    for actorDef <- app.actors do actorDefs.put(actorDef.actorType.value, actorDef)

    // -----------------------------------------------------------------------
    // Workflow/activity runtime (created only if needed)
    // -----------------------------------------------------------------------

    val workflowRuntime =
      if app.workflows.nonEmpty || app.activities.nonEmpty then
        val wb = new WorkflowRuntimeBuilder(workflowProperties)
        // WHY simple name: the workflow type appears in user-visible API URLs
        // (POST /v1.0-beta1/workflows/dapr/{type}/start) and in WorkflowName("...") values.
        // Users naturally use the simple class name ("OrderProcessingWorkflow"), not the
        // canonical name ("workflows.OrderProcessingWorkflow"), so we register under
        // getSimpleName so that the sidecar's dispatch matches what users pass to start().
        app.workflows.foreach { w =>
          wb.registerWorkflow(w.getClass.getSimpleName.nn, new WorkflowBridge(w), "", false)
        }
        app.activities.foreach { a =>
          wb.registerActivity(
            a.getClass.getCanonicalName.nn,
            new WorkflowActivityBridge(a, daprCapability.asInstanceOf[AnyRef]),
          )
        }
        val rt = wb.build()
        rt.start(false)
        rt
      else null

    // -----------------------------------------------------------------------
    // HTTP server
    // -----------------------------------------------------------------------

    val server = HttpServer.create(new InetSocketAddress(port), httpBacklog)
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())

    // Dapr sidecar calls GET /dapr/subscribe to discover pub/sub subscriptions.
    server.createContext(
      "/dapr/subscribe",
      exchange =>
        try
          if exchange.getRequestMethod.nn == "GET" then
            val arr = mapper.createArrayNode()
            pubSubEntries.asScala.foreach: e =>
              val obj = arr.addObject().put("pubsubname", e(0)).put("topic", e(1)).put("route", e(2))
              if e(3).nonEmpty then obj.put("deadLetterTopic", e(3))
            sendJson(exchange, 200, mapper.writeValueAsString(arr))
          else
            exchange.sendResponseHeaders(405, -1)
            exchange.getResponseBody.nn.close()
        catch
          case NonFatal(e) =>
            try sendJson(exchange, 500, errorJson(e))
            catch case NonFatal(e2) => log.log(Level.WARNING, "Failed to send error response for /dapr/subscribe", e2),
    )

    // Dapr sidecar calls GET /dapr/config to discover hosted actor types.
    server.createContext(
      "/dapr/config",
      exchange =>
        try
          if exchange.getRequestMethod.nn == "GET" then
            val types = actorDefs.keySet().asScala.toList.sorted
            val obj = mapper.createObjectNode()
            val entitiesArr = obj.putArray("entities")
            types.foreach(entitiesArr.add)
            obj.put("actorIdleTimeout", actorConfig.actorIdleTimeout.toGoString)
            obj.put("actorScanInterval", actorConfig.actorScanInterval.toGoString)
            obj.put("drainOngoingCallTimeout", actorConfig.drainOngoingCallTimeout.toGoString)
            obj.put("drainRebalancedActors", actorConfig.drainRebalancedActors)
            obj.put("remindersStoragePartitions", actorConfig.remindersStoragePartitions)
            val reentrancyObj = obj.putObject("reentrancy")
            reentrancyObj.put("enabled", actorConfig.reentrancy.enabled)
            reentrancyObj.put("maxStackDepth", actorConfig.reentrancy.maxStackDepth)
            if actorConfig.entitiesConfig.nonEmpty then
              val ecArr = obj.putArray("entitiesConfig")
              actorConfig.entitiesConfig.foreach: ec =>
                val entry = ecArr.addObject()
                val ecEntities = entry.putArray("entities")
                ec.entities.foreach(e => ecEntities.add(e.value))
                ec.actorIdleTimeout.foreach(v => entry.put("actorIdleTimeout", v.toGoString))
                ec.actorScanInterval.foreach(v => entry.put("actorScanInterval", v.toGoString))
                ec.drainOngoingCallTimeout.foreach(v => entry.put("drainOngoingCallTimeout", v.toGoString))
                ec.drainRebalancedActors.foreach(v => entry.put("drainRebalancedActors", v))
                ec.reentrancy.foreach: r =>
                  val rEntry = entry.putObject("reentrancy")
                  rEntry.put("enabled", r.enabled)
                  rEntry.put("maxStackDepth", r.maxStackDepth)
                ec.remindersStoragePartitions.foreach(v => entry.put("remindersStoragePartitions", v))
            sendJson(exchange, 200, mapper.writeValueAsString(obj))
          else
            exchange.sendResponseHeaders(405, -1)
            exchange.getResponseBody.nn.close()
        catch
          case NonFatal(e) =>
            try sendJson(exchange, 500, errorJson(e))
            catch case NonFatal(e2) => log.log(Level.WARNING, "Failed to send error response for /dapr/config", e2),
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
          try handleActorRequest(exchange, path, actorDefs, sidecarHttpEndpoint())
          catch
            case NonFatal(e) =>
              try sendJson(exchange, 500, errorJson(e))
              catch case NonFatal(e2) => log.log(Level.WARNING, s"Failed to send error response for $path", e2),
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
            // Exceptions from fn propagate to the outer catch below, which sends a 500 response.
            // Dapr retries the message on any non-2xx response, equivalent to SubscriptionResult.Retry.
            val result = fn(body)
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
                case NonFatal(e) =>
                  try sendJson(exchange, 500, errorJson(e))
                  catch case NonFatal(e2) => log.log(Level.WARNING, s"Failed to send error response for $path", e2)
            else
              val ivFn = invokeRoutes.get(path)
              if ivFn != null then
                val fn = ivFn.asInstanceOf[(String, String) => String]
                val resp = fn(exchange.getRequestMethod.nn, readBody(exchange))
                sendJson(exchange, 200, resp)
              else
                val jobFn = jobRoutes.get(path)
                if jobFn != null then
                  val fn = jobFn.asInstanceOf[String => Unit]
                  val body = readBody(exchange)
                  try
                    fn(body)
                    exchange.sendResponseHeaders(200, -1)
                    exchange.getResponseBody.nn.close()
                  catch
                    case NonFatal(e) =>
                      try sendJson(exchange, 500, errorJson(e))
                      catch case NonFatal(e2) => log.log(Level.WARNING, s"Failed to send error response for $path", e2)
                else
                  exchange.sendResponseHeaders(404, -1)
                  exchange.getResponseBody.nn.close()
        catch
          case NonFatal(e) =>
            try sendJson(exchange, 500, errorJson(e))
            catch case NonFatal(e2) => log.log(Level.WARNING, s"Failed to send error response for $path", e2),
    )

    server.start()

    // Register a JVM shutdown hook so the server drains in-flight requests
    // on SIGTERM/SIGINT before the JVM exits.
    Runtime.getRuntime.addShutdownHook(
      Thread
        .ofVirtual()
        .unstarted(() =>
          server.stop(shutdownGrace.toSeconds.toInt)
          if workflowRuntime != null then workflowRuntime.close(),
        ),
    )

    // Block the calling thread until it is interrupted (e.g. by a test or
    // by the shutdown hook completing), then propagate the interruption.
    try Thread.currentThread().join()
    catch
      case e: InterruptedException =>
        // WHY WE CATCH InterruptedException HERE
        // Same contract as MonoOps.awaitResult: Thread.join() clears the
        // interrupt flag when it throws InterruptedException.  We restore it
        // immediately and then stop the server cleanly before re-throwing.
        Thread.currentThread().interrupt()
        server.stop(shutdownGrace.toSeconds.toInt)
        throw e
    throw AssertionError("unreachable: Thread.join() on current thread blocks until interrupted")

@scala.caps.assumeSafe
private object DaprAppServer:

  private val log: Logger = Logger.getLogger("dapr4s.internal.DaprAppServer").nn
  private val mapper: ObjectMapper = Json.mapper

  // -------------------------------------------------------------------------
  // Actor request dispatch
  // -------------------------------------------------------------------------

  private def handleActorRequest(
      exchange: HttpExchange,
      path: String,
      actorDefs: JHashMap[String, ActorDefinition],
      sidecarHttpEndpoint: URI,
  ): Unit =
    // Path patterns (after stripping leading /actors/):
    //   {type}/{id}/method/{name}              — method invocation
    //   {type}/{id}/method/remind/{name}       — reminder callback
    //   {type}/{id}/method/timer/{name}        — timer callback
    //   DELETE {type}/{id}                     — deactivation (return 200)
    val parts = path.stripPrefix("/actors/").split("/", -1)
    parts match
      case Array(actorType, actorId, "method", methodName) if methodName != "remind" && methodName != "timer" =>
        dispatchActorMethod(exchange, actorType, actorId, methodName, actorDefs, sidecarHttpEndpoint)

      case Array(actorType, actorId, "method", "remind", reminderName) =>
        dispatchActorReminder(exchange, actorType, actorId, reminderName, actorDefs, sidecarHttpEndpoint)

      case Array(actorType, actorId, "method", "timer", timerName) =>
        dispatchActorTimer(exchange, actorType, actorId, timerName, actorDefs, sidecarHttpEndpoint)

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
      sidecarHttpEndpoint: URI,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then
      exchange.sendResponseHeaders(404, -1)
      exchange.getResponseBody.nn.close()
    else
      val ctx = HttpActorContext(ActorType(actorType), ActorId(actorId), sidecarHttpEndpoint)
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
      sidecarHttpEndpoint: URI,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then
      exchange.sendResponseHeaders(404, -1)
      exchange.getResponseBody.nn.close()
    else
      val ctx = HttpActorContext(ActorType(actorType), ActorId(actorId), sidecarHttpEndpoint)
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
      sidecarHttpEndpoint: URI,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then
      exchange.sendResponseHeaders(404, -1)
      exchange.getResponseBody.nn.close()
    else
      val ctx = HttpActorContext(ActorType(actorType), ActorId(actorId), sidecarHttpEndpoint)
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

  private def parseHttpMethod(s: String): HttpMethod = s.toUpperCase match
    case "GET"     => HttpMethod.Get
    case "POST"    => HttpMethod.Post
    case "PUT"     => HttpMethod.Put
    case "PATCH"   => HttpMethod.Patch
    case "DELETE"  => HttpMethod.Delete
    case "HEAD"    => HttpMethod.Head
    case "OPTIONS" => HttpMethod.Options
    case _         => HttpMethod.Post

  private def readBody(exchange: HttpExchange): String =
    new String(exchange.getRequestBody.nn.readAllBytes().nn, "UTF-8")

  private def sendJson(exchange: HttpExchange, code: Int, body: String): Unit =
    val bytes = body.getBytes("UTF-8").nn
    exchange.getResponseHeaders.nn.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    val out = exchange.getResponseBody.nn
    out.write(bytes)
    out.close()

  private def errorJson(e: Throwable): String =
    val name = e.getClass.getSimpleName.nn
    val error = if name.nonEmpty then name else e.getClass.getName.nn
    val node = mapper.createObjectNode()
    node.put("error", error)
    Option(e.getMessage).foreach(node.put("error_description", _))
    mapper.writeValueAsString(node)

  /** Decode the `data` field from a reminder/timer callback body.
    *
    * The Dapr sidecar sends `{"data":"base64-encoded-json","dueTime":"...","period":"..."}`. We base64-decode the
    * `data` field and then JSON-decode it with the route's codec.
    */
  private def decodeCallbackPayload[T](body: String, codec: JsonCodec[T]): T =
    try
      val env = mapper.readTree(body)
      val data = Option(env.get("data")).map(_.asText("")).getOrElse("")
      val json =
        if data.isEmpty then "null"
        else new String(java.util.Base64.getDecoder.nn.decode(data).nn, "UTF-8")
      codec.decode(json).fold(err => throw RuntimeException("Failed to decode callback payload", err), identity)
    catch
      case e: RuntimeException            => throw e
      case scala.util.control.NonFatal(e) =>
        throw RuntimeException("Failed to parse callback body", e)

  /** Decode the payload of an inbound job trigger (`POST /job/<name>`).
    *
    * Dapr delivers the job's stored data as the request body. Depending on the sidecar version and how the job was
    * scheduled, the body is either the raw JSON payload or an envelope of the form `{"data": ...}`. We try the raw form
    * first and fall back to unwrapping a top-level `data` field so both shapes work.
    */
  private def decodeJobPayload[T](body: String, codec: JsonCodec[T]): Either[JsonDecodeException, T] =
    val json = if body.isEmpty then "null" else body
    codec.decode(json) match
      case r @ Right(_)   => r
      case Left(firstErr) =>
        try
          val env = mapper.readTree(json)
          if env != null && env.has("data") then
            val data = env.get("data").nn
            val inner = if data.isTextual then data.asText("") else mapper.writeValueAsString(data)
            codec.decode(inner)
          else Left(firstErr)
        catch case NonFatal(_) => Left(firstErr)

  private def parseCloudEvent[T](
      bodyJson: String,
      codec: JsonCodec[T],
      defaultPubsubName: PubSubName,
      defaultTopic: Topic,
      handler: CloudEvent[T] => SubscriptionResult,
  ): SubscriptionResult =
    val env = mapper.readTree(bodyJson)
    val data = Option(env.get("data")).map(mapper.writeValueAsString).getOrElse("null")
    codec.decode(data) match
      case Left(_)  => SubscriptionResult.Drop
      case Right(v) =>
        handler(
          CloudEvent[T](
            id = CloudEventId(Option(env.get("id")).map(_.asText("")).filter(_.nonEmpty).getOrElse("unknown")),
            source =
              CloudEventSource(Option(env.get("source")).map(_.asText("")).filter(_.nonEmpty).getOrElse("unknown")),
            specVersion = CloudEventSpecVersion(
              Option(env.get("specversion")).map(_.asText("")).filter(_.nonEmpty).getOrElse("1.0"),
            ),
            eventType =
              CloudEventType(Option(env.get("type")).map(_.asText("")).filter(_.nonEmpty).getOrElse("unknown")),
            topic = Option(env.get("topic")).map(s => Topic(s.asText(""))).getOrElse(defaultTopic),
            pubSubName = Option(env.get("pubsubname")).map(s => PubSubName(s.asText(""))).getOrElse(defaultPubsubName),
            dataContentType = ContentType(
              Option(env.get("datacontenttype")).map(_.asText("")).filter(_.nonEmpty).getOrElse("application/json"),
            ),
            data = v,
          ),
        )
