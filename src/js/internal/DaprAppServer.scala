//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import java.util.{ArrayList, HashMap as JHashMap}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.scalajs.js
import scala.util.control.NonFatal
import org.scalablytyped.runtime.Instantiable1
import typings.expressServeStaticCore.mod.{Express, Handler, ParamsDictionary, Request, Response}
import typings.node.globalsMod.global as NodeGlobals
import typings.node.httpMod.{IncomingMessage, Server, ServerResponse}
import typings.node.nodeColonnetMod.Socket
import typings.node.processMod.global.NodeJS.Signals
import typings.qs.mod.ParsedQs
import typings.std.Record

/** HTTP server that serves the Dapr app-channel protocol from a [[DaprApp]] description — the Scala.js twin of the JVM
  * `DaprAppServer`, identical route-for-route and status-code-for-status-code.
  *
  * The [[DaprApp]] is provided at construction time. Dispatch tables are built from it inside [[startAndBlock]]; until
  * then the object is immutable.
  *
  * ==Why express, not the SDK's `DaprServer`==
  *
  * The JVM twin deliberately bypasses the Java SDK's server and hand-rolls the app-channel protocol on
  * `com.sun.net.httpserver`; this class mirrors that design on express 4 (a dependency of `@dapr/dapr`, so always
  * installed), typed by the ScalablyTyped `@types/express` conversion (plus the one hand-written shim,
  * [[facade.ExpressModule]] — see its header for why). The JS SDK's `DaprServer` is unsuitable for the same reasons
  * its Java counterpart was: its pub/sub callbacks strip the CloudEvent envelope (dapr4s hands the full envelope to
  * subscription handlers) and its invocation listener constrains HTTP verbs (dapr4s accepts every verb and reports it
  * in [[dapr4s.InvokeRequest]]). The SDK remains the backend for all '''outbound''' capabilities.
  *
  * ==Request model==
  *
  * A single `express.text` middleware (catch-all media type, effectively unlimited size — the JVM reads bodies
  * unbounded too) makes `req.body` the raw body string, so JSON parsing stays in this file exactly like the JVM's
  * `readBody` + Jackson. Every handler immediately enters a fresh `js.async { ... }` block before touching dapr4s
  * dispatch — the per-request analogue of the JVM's virtual-thread-per-request executor, and a JSPI requirement: the
  * express router invokes handlers from a JavaScript frame, and suspension (any capability call in user handler code is
  * an orphan `js.await`) cannot cross a JS frame, so each request needs its own `js.async` entry (see
  * [[ConfigurationCapabilityImpl.subscribe]] for the canonical explanation).
  *
  * ==Differences forced by express (documented, deliberate)==
  *
  *   - The JVM's catch-all context dispatches pub/sub, binding, invocation and job paths for '''any''' HTTP verb;
  *     express routes here are registered with `app.all` to preserve exactly that (the OPTIONS probe daprd sends to
  *     input-binding routes must not 404, or the binding is never registered). Actor routes follow the protocol verbs
  *     (PUT method/remind/timer, DELETE deactivate) instead of the JVM's verb-agnostic prefix context.
  *   - Express matches `/dapr/subscribe` and `/dapr/config` exactly; the JVM's prefix-matching `HttpServer` would also
  *     answer sub-paths of them (never requested by the sidecar).
  *   - User route paths are passed to express verbatim; path strings containing `path-to-regexp` pattern characters
  *     (`:`/`*`/`(`) would be interpreted as patterns rather than the JVM's exact-string match.
  *   - `httpBacklog == 0` means "OS default" on the JVM; Node has no such sentinel, so 0 falls back to Node's default
  *     backlog (511) by omitting the argument.
  */
@scala.caps.assumeSafe
private[dapr4s] final class DaprAppServer(app: DaprApp):

  import DaprAppServer.*

  /** Build the dispatch tables, register every route on a fresh express app, start the workflow host if needed, bind
    * the port, and suspend forever.
    *
    * "Blocking forever" is implemented by orphan-awaiting a promise that never resolves — the JS analogue of the JVM
    * twin's `Thread.currentThread().join()`. The express server keeps the Node event loop alive regardless; the
    * suspended Wasm stack exists to preserve `serve()`'s `Nothing` contract and to surface server `"error"` events
    * (e.g. `EADDRINUSE`) as a thrown exception, like `HttpServer.create`'s synchronous `BindException` on the JVM.
    *
    * Shutdown mirrors the JVM hook in spirit: SIGINT/SIGTERM stop the listener, in-flight requests drain (Node's
    * `server.close` callback), the workflow host closes, and after at most `shutdownGrace` the process exits — the
    * bounded-drain semantics of the JVM's `server.stop(grace)`. One divergence: the JVM hook closes the workflow
    * runtime only '''after''' the drain completes; here the close is initiated as soon as the listener stops accepting,
    * because nothing can block a JS signal listener until the drain finishes.
    */
  def startAndBlock(
      port: Int,
      daprCapability: DaprCapability,
      sidecar: SidecarConfig,
      shutdownGrace: FiniteDuration = 2.seconds,
      httpBacklog: Int = 0,
      actorConfig: ActorRuntimeConfig = ActorRuntimeConfig(),
  ): Nothing =

    // -----------------------------------------------------------------------
    // Dispatch tables from DaprApp (the JVM keeps closures in JHashMaps to stay
    // CC-opaque; here each closure goes straight into an express route handler,
    // so only the /dapr/subscribe entry list and the actor-definition lookup
    // table survive as tables).
    // -----------------------------------------------------------------------

    // For /dapr/subscribe — ordered list of (pubsubName, topic, route, deadLetterTopic)
    // entries; the 4th element is "" (empty) when no dead-letter topic is configured.
    val pubSubEntries: ArrayList[Array[String]] = ArrayList()

    // actorType → actorDefinition
    val actorDefs: JHashMap[String, ActorDefinition] = JHashMap()
    for actorDef <- app.actors do actorDefs.put(actorDef.actorType.value, actorDef)

    // -----------------------------------------------------------------------
    // Workflow/activity host (created only if needed, like the JVM runtime)
    // -----------------------------------------------------------------------

    val workflowHost: Option[WorkflowHost.Handle] =
      if app.workflows.nonEmpty || app.activities.nonEmpty then
        Some(WorkflowHost.start(app.workflows, app.activities, daprCapability, sidecar))
      else None

    // -----------------------------------------------------------------------
    // HTTP server — registration order encodes the JVM's dispatch precedence:
    // exact framework paths, then the /actors prefix, then user routes in the
    // JVM catch-all's check order (pub/sub, bindings, invocations, jobs), then
    // the empty-404 fallback.
    // -----------------------------------------------------------------------

    val expressApp = facade.ExpressModule()
    expressApp.mount(
      facade.ExpressModule.text(new facade.ExpressTextOptions(`type` = "*/*", limit = Double.MaxValue)),
    )

    // Dapr sidecar calls GET /dapr/subscribe to discover pub/sub subscriptions.
    // app.all + in-handler method check mirrors the JVM's 405 for non-GET verbs.
    expressApp.allRoute(
      "/dapr/subscribe",
      (req, res, _) =>
        handleAsync(res, "/dapr/subscribe") { () =>
          if req.method.contains("GET") then
            val arr = js.Array[js.Any]()
            pubSubEntries.asScala.foreach: e =>
              val obj = js.Dictionary[js.Any]("pubsubname" -> e(0), "topic" -> e(1), "route" -> e(2))
              if e(3).nonEmpty then obj("deadLetterTopic") = e(3)
              arr.push(obj)
            sendJson(res, 200, js.JSON.stringify(arr))
          else sendEmpty(res, 405)
        },
    )

    // Dapr sidecar calls GET /dapr/config to discover hosted actor types.
    // Served unconditionally (even with no actors), exactly like the JVM.
    expressApp.allRoute(
      "/dapr/config",
      (req, res, _) =>
        handleAsync(res, "/dapr/config") { () =>
          if req.method.contains("GET") then sendJson(res, 200, actorConfigJson(actorDefs, actorConfig))
          else sendEmpty(res, 405)
        },
    )

    // Actor routes: PUT  /actors/{type}/{id}/method/{name}
    //               PUT  /actors/{type}/{id}/method/remind/{name}
    //               PUT  /actors/{type}/{id}/method/timer/{name}
    //               DELETE /actors/{type}/{id}
    // Registered only when actors exist, like the JVM's conditional "/actors" context.
    // The remind/timer routes are registered first; their 5-segment paths cannot be
    // claimed by the 4-segment {name} pattern (params never span a "/").
    if actorDefs.size() > 0 then
      expressApp.putRoute(
        "/actors/:actorType/:actorId/method/remind/:reminderName",
        (req, res, _) =>
          handleAsync(res, req.path) { () =>
            dispatchActorReminder(
              res,
              param(req, "actorType"),
              param(req, "actorId"),
              param(req, "reminderName"),
              readBody(req),
              actorDefs,
              sidecar,
            )
          },
      )
      expressApp.putRoute(
        "/actors/:actorType/:actorId/method/timer/:timerName",
        (req, res, _) =>
          handleAsync(res, req.path) { () =>
            dispatchActorTimer(
              res,
              param(req, "actorType"),
              param(req, "actorId"),
              param(req, "timerName"),
              readBody(req),
              actorDefs,
              sidecar,
            )
          },
      )
      expressApp.putRoute(
        "/actors/:actorType/:actorId/method/:methodName",
        (req, res, _) =>
          handleAsync(res, req.path) { () =>
            val methodName = param(req, "methodName")
            // Mirror the JVM's pattern guard (`methodName != "remind" && methodName != "timer"`):
            // a 4-segment path ending in one of the reserved callback names is not a method
            // invocation and falls through to 404 there.
            if methodName == "remind" || methodName == "timer" then sendEmpty(res, 404)
            else
              dispatchActorMethod(
                res,
                param(req, "actorType"),
                param(req, "actorId"),
                methodName,
                readBody(req),
                actorDefs,
                sidecar,
              )
          },
      )
      expressApp.deleteRoute(
        "/actors/:actorType/:actorId",
        (_, res, _) =>
          handleAsync(res, "/actors") { () =>
            // Actor deactivation — no cleanup needed in our model (same as the JVM).
            sendEmpty(res, 200)
          },
      )

    // Pub/sub delivery routes. app.all (not app.post) keeps the JVM's verb-agnostic
    // catch-all dispatch — the sidecar only ever POSTs here.
    for sub <- app.subscriptions do
      val path = if sub.route.value.startsWith("/") then sub.route.value else "/" + sub.route.value
      val handler = sub.rawHandler.asInstanceOf[CloudEvent[sub.Payload] => SubscriptionResult]
      pubSubEntries.add(
        Array(sub.pubsubName.value, sub.topic.value, path, sub.deadLetterTopic.map(_.value).getOrElse("")),
      )
      val fn: String => SubscriptionResult = bodyJson =>
        parseCloudEvent(bodyJson, sub.codec, sub.pubsubName, sub.topic, handler)
      expressApp.allRoute(
        path,
        erased((req, res, _) =>
          handleAsync(res, path) { () =>
            // Exceptions from fn propagate to handleAsync's catch, which sends a 500 response.
            // Dapr retries the message on any non-2xx response, equivalent to SubscriptionResult.Retry.
            val result = fn(readBody(req))
            val status = result match
              case SubscriptionResult.Success => "SUCCESS"
              case SubscriptionResult.Retry   => "RETRY"
              case SubscriptionResult.Drop    => "DROP"
            sendJson(res, 200, s"""{"status":"$status"}""")
          },
        ),
      )

    // Input-binding routes. app.all is load-bearing here: on startup daprd probes each
    // binding route with an OPTIONS request and registers the binding only on a non-404
    // answer — the JVM's verb-agnostic dispatch answers that probe (with a 500 decode
    // failure, which daprd accepts), and so does this.
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
      expressApp.allRoute(
        path,
        erased((req, res, _) =>
          handleAsync(res, path) { () =>
            fn(readBody(req))
            sendEmpty(res, 200)
          },
        ),
      )

    // Service-invocation routes — every verb dispatches (app.all), and the verb is
    // surfaced through InvokeRequest for withRequest handlers, exactly like the JVM.
    for inv <- app.invokeRoutes do
      val path = "/" + inv.methodName.value
      val fn: (String, String) => String =
        if inv.usesRequestEnvelope then
          val handler = inv.rawHandler.asInstanceOf[InvokeRequest[inv.Req] => inv.Resp]
          (methodStr, bodyJson) =>
            inv.reqCodec.decode(if bodyJson.isEmpty then "null" else bodyJson) match
              case Right(req) =>
                inv.respCodec.encode(handler(InvokeRequest(inv.methodName, parseHttpMethod(methodStr), req)))
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
      expressApp.allRoute(
        path,
        erased((req, res, _) =>
          // Server requests always carry a method; "" is the defensive fallback for the UndefOr
          // the ScalablyTyped IncomingMessage type declares (it parses as Post downstream).
          handleAsync(res, path)(() => sendJson(res, 200, fn(req.method.getOrElse(""), readBody(req)))),
        ),
      )

    // Job trigger routes (POST /job/<name> from the sidecar; verb-agnostic like the JVM).
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
      expressApp.allRoute(
        path,
        erased((req, res, _) =>
          handleAsync(res, path) { () =>
            fn(readBody(req))
            sendEmpty(res, 200)
          },
        ),
      )

    // Fallback for everything unrouted: the JVM catch-all's empty-bodied 404
    // (replacing express's default HTML "Cannot GET ..." page).
    expressApp.mount((_, res, _) => sendEmpty(res, 404))

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    val server: HttpServer =
      if httpBacklog > 0 then
        // WHAT: js.Dynamic invocation of listen(port, backlog, callback) + asInstanceOf on its result.
        // WHY: Node's net.Server.listen argument normalisation takes a numeric second argument as the
        // backlog (`toNumber(args[1])` in net.js), but @types/node — and therefore the ScalablyTyped
        // surface — only declares the (port, hostname, backlog, callback) arity; passing a hostname to
        // reach the typed backlog overload would change the bind address (a behaviour change from the
        // hand facade, which used this same arity).
        // WHY SAFE: the arity is documented stable Node behaviour, runtime-verified when the hand facade
        // was written; the dynamic call invokes the very same `listen` member, and the result is the same
        // http.Server the typed overloads return.
        expressApp
          .asInstanceOf[js.Dynamic]
          .applyDynamic("listen")(port, httpBacklog, ((() => ()): js.Function0[Unit]))
          .asInstanceOf[HttpServer]
      else expressApp.listen(port.toDouble, () => ())

    // SIGINT/SIGTERM take over Node's default terminate-on-signal: stop accepting,
    // drain, close the workflow host, exit — force-exiting after shutdownGrace at
    // the latest (the JVM hook's server.stop(grace) bounded drain).
    def shutdown(): Unit =
      closeServer(server)(() => NodeGlobals.process.exit(0))
      workflowHost.foreach { h =>
        // The JVM hook lets a workflow-runtime close failure kill the hook thread (it is
        // merely logged by the JVM); an uncaught error in a Node signal listener would
        // instead abort the process before the connection drain — so log and continue.
        try h.close()
        catch
          case NonFatal(e) =>
            js.Dynamic.global.console.warn(s"dapr4s: workflow host close failed during shutdown: $e"): Unit
      }
      js.timers.setTimeout(shutdownGrace)(NodeGlobals.process.exit(0)): Unit
    NodeGlobals.process.on(Signals.SIGINT, (_: Signals) => shutdown()): Unit
    NodeGlobals.process.on(Signals.SIGTERM, (_: Signals) => shutdown()): Unit

    // Suspend this stack forever (the JS Thread.currentThread().join() — see the method
    // scaladoc). The promise never resolves; it rejects only on a server "error" event,
    // making a failed bind throw out of serve() like the JVM's BindException.
    val serverFailure: js.Promise[Nothing] = new js.Promise[Nothing]((_, reject) =>
      server.on_error(
        typings.node.nodeStrings.error,
        (err: js.Error) => {
          reject(err)
          ()
        },
      ): Unit,
    )
    try JsAwait.await(serverFailure)
    catch
      case NonFatal(e) =>
        // The workflow host started BEFORE the bind; if the bind (or the server) fails, this is
        // the only exit path, and without the close the detached gRPC work-item stream would keep
        // executing activities against a torn-down capability scope and keep the Node event loop
        // alive after serve() has thrown. close() is idempotent and non-suspending (see
        // WorkflowHost.Handle), so calling it here — inside a catch, outside any JS frame — is
        // safe; its own failure is swallowed (NonFatal only) so the original server error stays
        // the primary exception. The JVM twin shares this start-host-before-bind ordering but
        // leaks its runtime via non-daemon threads on a bind failure — a candidate for a separate
        // follow-up fix there.
        workflowHost.foreach { h =>
          try h.close()
          catch case NonFatal(_) => ()
        }
        throw e

@scala.caps.assumeSafe
private object DaprAppServer:

  // -------------------------------------------------------------------------
  // ScalablyTyped express plumbing
  // -------------------------------------------------------------------------

  /** The request/response instantiation dapr4s uses everywhere: express's own defaults (`ParamsDictionary` route
    * params, `Any` bodies, `ParsedQs` query, `Record[String, Any]` locals) — the same parameters the ST `Handler`
    * alias pins, spelled out so the helper signatures below stay readable.
    */
  private type ExpressRequest = Request[ParamsDictionary, Any, Any, ParsedQs, Record[String, Any]]
  private type ExpressResponse = Response[Any, Record[String, Any], Double]

  /** The `http.Server` instantiation `Application.listen` returns in the ST typing. */
  private type HttpServer = Server[
    Instantiable1[/* socket */ Socket, IncomingMessage],
    Instantiable1[/* req */ Any, ServerResponse[IncomingMessage]],
  ]

  /** Route/middleware registration helpers.
    *
    * ScalablyTyped renders every express registration method with explicit `[P, ResBody, ReqBody, ReqQuery,
    * LocalsObj]` type parameters whose TS defaults do not survive conversion ("(This generic is meant to be passed
    * explicitly.)" in the generated sources) — these extensions pin them once to the [[ExpressRequest]] instantiation
    * so the call sites in `startAndBlock` stay as readable as with the old hand facade. `mount` uses the
    * `@JSName("use")` explicit-generic variant for the same reason: the parameterless-path `use` overload is typed
    * against `RouteParameters[String]` params, which a `Handler` (params = `ParamsDictionary`) does not satisfy under
    * invariance.
    */
  extension (app: Express)
    private def allRoute(path: String, handler: Handler): Unit =
      app.all[ParamsDictionary, Any, Any, ParsedQs, Record[String, Any]](path, handler): Unit
    private def putRoute(path: String, handler: Handler): Unit =
      app.put[ParamsDictionary, Any, Any, ParsedQs, Record[String, Any]](path, handler): Unit
    private def deleteRoute(path: String, handler: Handler): Unit =
      app.delete[ParamsDictionary, Any, Any, ParsedQs, Record[String, Any]](path, handler): Unit
    private def mount(handler: Handler): Unit =
      app.use_PResBodyReqBodyReqQueryLocalsObj[ParamsDictionary, Any, Any, ParsedQs, Record[String, Any]](
        handler,
      ): Unit

  /** Stop accepting new connections; `callback` fires once all in-flight connections have closed.
    *
    * WHAT: asInstanceOf viewing the http server as the ST `net.Server` to reach `close`.
    *
    * WHY: the ScalablyTyped `@types/node` conversion drops `http.Server`'s `net.Server`/`EventEmitter` parents (the
    * generated class extends only `StObject`), so `close` is missing from the [[HttpServer]] type even though every
    * Node `http.Server` has it.
    *
    * WHY SAFE: `http.Server extends net.Server` is the documented, stable Node class hierarchy; the cast only
    * switches the compile-time view of the very same object, and `close(cb)` is the same member the hand facade
    * called (Node invokes the callback with an optional error, which the JVM-parity shutdown path ignores —
    * `server.stop(grace)` has no error channel either).
    */
  private def closeServer(server: HttpServer)(onClosed: () => Unit): Unit =
    server.asInstanceOf[typings.node.netMod.Server].close((_: js.UndefOr[js.Error]) => onClosed()): Unit

  // -------------------------------------------------------------------------
  // Per-request async entry
  // -------------------------------------------------------------------------

  /** WHAT: `asInstanceOf` erasing an express handler lambda's inferred capture set (`Handler^` accepts any capturing
    * handler; the cast forgets the set).
    *
    * WHY: the ST `Handler` is a `js.Function3` — a Scala-defined SAM, so CC tracks the closure's captures — and the
    * route handlers for user-defined routes capture their dispatch closure (`fn`), which transitively reaches the
    * enclosing `DaprAppServer`. The express registration signatures mirror express's JavaScript callback type, which
    * is necessarily capture-free — a JS interop boundary cannot carry capture annotations.
    *
    * WHY SAFE: the handler cannot outlive what it captures: it only runs while the express server is listening, and the
    * server lives for the entire process lifetime — `startAndBlock` never returns normally (shutdown exits the
    * process), and its only exceptional exit (bind/server failure) stops the workflow runtime before unwinding, after
    * which the failed server never invokes a handler. Same erasure rationale as
    * `ConfigurationCapabilityImpl.subscribe`'s callback cast and the `AnyRef`-erasure pattern documented in AGENTS.md.
    */
  private def erased(handler: Handler^): Handler =
    handler.asInstanceOf[Handler]

  /** Run `dispatch` inside a fresh `js.async { ... }` entry and convert any non-fatal failure into the JVM twin's
    * 500-with-error-JSON response (with the same warn-and-give-up fallback if even that send fails).
    *
    * The returned promise must never reach express as an unhandled rejection (Node terminates the process on unhandled
    * rejections by default). Non-fatal throwables are handled exhaustively inside the block; the trailing `catch`
    * covers the only remaining escape route — '''fatal''' throwables (`LinkageError` and friends, which `NonFatal`
    * deliberately refuses to catch) — by logging them loudly instead of crashing the server, the moral equivalent of a
    * JVM virtual thread dying with an uncaught-exception report while the server lives on.
    */
  private def handleAsync(res: ExpressResponse, path: String)(dispatch: () => Unit): Unit =
    val completion = js.async {
      try dispatch()
      catch
        case NonFatal(e) =>
          try sendJson(res, 500, errorJson(e))
          catch
            case NonFatal(e2) =>
              js.Dynamic.global.console.warn(s"dapr4s: failed to send error response for $path: $e2"): Unit
    }
    val onFatal: js.Function1[Any, Unit] = err =>
      js.Dynamic.global.console.error(s"dapr4s: fatal error escaped the request handler for $path: $err"): Unit
    completion.`catch`[Unit](onFatal): Unit

  // -------------------------------------------------------------------------
  // Actor request dispatch
  // -------------------------------------------------------------------------

  private def dispatchActorMethod(
      res: ExpressResponse,
      actorType: String,
      actorId: String,
      methodName: String,
      body: String,
      actorDefs: JHashMap[String, ActorDefinition],
      sidecar: SidecarConfig,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then sendEmpty(res, 404)
    else
      val ctx = HttpActorContext(ActorType(actorType), ActorId(actorId), sidecar)
      val routes = defn.build(ActorId(actorId), ctx)
      val route = routes.methods.find(_.methodName.value == methodName).orNull
      if route == null then sendEmpty(res, 404)
      else
        val handler = route.rawHandler.asInstanceOf[route.Req => route.Resp]
        route.reqCodec.decode(if body.isEmpty then "null" else body) match
          case Left(_)    => sendEmpty(res, 400)
          case Right(req) => sendJson(res, 200, route.respCodec.encode(handler(req)))

  private def dispatchActorReminder(
      res: ExpressResponse,
      actorType: String,
      actorId: String,
      reminderName: String,
      body: String,
      actorDefs: JHashMap[String, ActorDefinition],
      sidecar: SidecarConfig,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then sendEmpty(res, 404)
    else
      val ctx = HttpActorContext(ActorType(actorType), ActorId(actorId), sidecar)
      val routes = defn.build(ActorId(actorId), ctx)
      val route = routes.reminders.find(_.reminderName.value == reminderName).orNull
      if route == null then
        // Reminder delivered but no handler registered — acknowledge it silently
        sendEmpty(res, 200)
      else
        val handler = route.rawHandler.asInstanceOf[route.Payload => Unit]
        handler(decodeCallbackPayload(body, route.codec))
        sendEmpty(res, 200)

  private def dispatchActorTimer(
      res: ExpressResponse,
      actorType: String,
      actorId: String,
      timerName: String,
      body: String,
      actorDefs: JHashMap[String, ActorDefinition],
      sidecar: SidecarConfig,
  ): Unit =
    val defn = actorDefs.get(actorType)
    if defn == null then sendEmpty(res, 404)
    else
      val ctx = HttpActorContext(ActorType(actorType), ActorId(actorId), sidecar)
      val routes = defn.build(ActorId(actorId), ctx)
      val route = routes.timers.find(_.timerName.value == timerName).orNull
      if route == null then sendEmpty(res, 200)
      else
        val handler = route.rawHandler.asInstanceOf[route.Payload => Unit]
        handler(decodeCallbackPayload(body, route.codec))
        sendEmpty(res, 200)

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

  /** A named route parameter; defensively "" when absent (express always populates declared params). */
  private def param(req: ExpressRequest, name: String): String =
    req.params.get(name).getOrElse("")

  /** The raw request body, mirroring the JVM `readBody`: the string captured by the `express.text` middleware, or ""
    * for the requests body-parser skips (no body / no Content-Type), where `req.body` is its `{}` placeholder object.
    * (The ST type of `req.body` is the `ReqBody = Any` type parameter, hence the type test rather than a cast.)
    */
  private def readBody(req: ExpressRequest): String =
    req.body match
      case s: String => s
      case _         => ""

  private def sendJson(res: ExpressResponse, code: Int, body: String): Unit =
    res.status(code).`type`("application/json").send(body): Unit

  /** Status code with an empty body — the JVM's `exchange.sendResponseHeaders(code, -1)`.
    *
    * WHAT: js.Dynamic call of `res.end()`.
    *
    * WHY: `end()` lives on Node's `OutgoingMessage`/`stream.Writable`, whose parent links the ScalablyTyped
    * `@types/node` conversion drops (the generated `ServerResponse` extends only `StObject`), so no typed member
    * exists. The typed alternatives change behaviour: `res.send()`/`res.send("")` add `Content-Type`/`ETag` headers,
    * `res.sendStatus(code)` writes the status text as a body.
    *
    * WHY SAFE: `end()` is the documented, stable way to finish a Node response without a body (inherited
    * `ServerResponse.end`), exactly what the hand facade called; the dynamic call invokes the same member on the
    * same object.
    */
  private def sendEmpty(res: ExpressResponse, code: Int): Unit =
    res.status(code).asInstanceOf[js.Dynamic].applyDynamic("end")(): Unit

  private def errorJson(e: Throwable): String =
    val name = e.getClass.getSimpleName.nn
    val error = if name.nonEmpty then name else e.getClass.getName.nn
    val obj = js.Dictionary[js.Any]("error" -> error)
    Option(e.getMessage).foreach(m => obj("error_description") = m)
    js.JSON.stringify(obj)

  /** The `GET /dapr/config` actor-runtime JSON, field-for-field as the JVM emits it (Go-duration strings via
    * [[dapr4s.DaprDuration.toGoString]], same key order, `entitiesConfig` only when non-empty).
    */
  private def actorConfigJson(actorDefs: JHashMap[String, ActorDefinition], actorConfig: ActorRuntimeConfig): String =
    val types = actorDefs.keySet().asScala.toList.sorted
    val obj = js.Dictionary[js.Any]()
    obj("entities") = js.Array(types*)
    obj("actorIdleTimeout") = actorConfig.actorIdleTimeout.toGoString
    obj("actorScanInterval") = actorConfig.actorScanInterval.toGoString
    obj("drainOngoingCallTimeout") = actorConfig.drainOngoingCallTimeout.toGoString
    obj("drainRebalancedActors") = actorConfig.drainRebalancedActors
    obj("remindersStoragePartitions") = actorConfig.remindersStoragePartitions
    obj("reentrancy") = js.Dictionary[js.Any](
      "enabled" -> actorConfig.reentrancy.enabled,
      "maxStackDepth" -> actorConfig.reentrancy.maxStackDepth,
    )
    if actorConfig.entitiesConfig.nonEmpty then
      val ecArr = js.Array[js.Any]()
      actorConfig.entitiesConfig.foreach: ec =>
        val entry = js.Dictionary[js.Any]()
        entry("entities") = js.Array(ec.entities.map(_.value)*)
        ec.actorIdleTimeout.foreach(v => entry("actorIdleTimeout") = v.toGoString)
        ec.actorScanInterval.foreach(v => entry("actorScanInterval") = v.toGoString)
        ec.drainOngoingCallTimeout.foreach(v => entry("drainOngoingCallTimeout") = v.toGoString)
        ec.drainRebalancedActors.foreach(v => entry("drainRebalancedActors") = v)
        ec.reentrancy.foreach: r =>
          entry("reentrancy") = js.Dictionary[js.Any]("enabled" -> r.enabled, "maxStackDepth" -> r.maxStackDepth)
        ec.remindersStoragePartitions.foreach(v => entry("remindersStoragePartitions") = v)
        ecArr.push(entry)
      obj("entitiesConfig") = ecArr
    js.JSON.stringify(obj)

  /** Decode the `data` field from a reminder/timer callback body.
    *
    * The Dapr sidecar sends `{"data":"base64-encoded-json","dueTime":"...","period":"..."}`. We base64-decode the
    * `data` field and then JSON-decode it with the route's codec — same as the JVM twin.
    *
    * Structured slightly differently from the JVM's try/catch ordering because `js.JavaScriptException` (what a
    * `JSON.parse` failure surfaces as) extends `RuntimeException`, so the JVM's "rethrow RuntimeException, wrap the
    * rest" trick cannot distinguish parse failures here; the envelope parse gets its own wrap instead. Net behaviour is
    * identical: parse failure → wrapped "Failed to parse callback body", decode failure → "Failed to decode callback
    * payload", invalid base64 → raw `IllegalArgumentException` (a `RuntimeException` the JVM also rethrows unwrapped).
    */
  private def decodeCallbackPayload[T](body: String, codec: JsonCodec[T]): T =
    val dataB64 =
      try
        val env = js.JSON.parse(body)
        if (env: Any) == null || js.typeOf(env) != "object" then ""
        else
          // WHAT: asInstanceOf on a js.JSON.parse result.
          // WHY: JSON.parse is typed js.Any; we need property access on it.
          // WHY SAFE: js.Dynamic is the untyped view of any JS value — the cast is a no-op at
          // runtime, and the read below is type-tested before being trusted (see JsInterop.sdkFailureOf).
          (env.asInstanceOf[js.Dynamic].selectDynamic("data"): Any) match
            case s: String => s
            case _         => "" // absent / null / non-string data — same as Jackson's asText("") fallback
      catch case NonFatal(e) => throw RuntimeException("Failed to parse callback body", e)
    val json =
      if dataB64.isEmpty then "null"
      else new String(java.util.Base64.getDecoder.nn.decode(dataB64).nn, "UTF-8")
    codec.decode(json).fold(err => throw RuntimeException("Failed to decode callback payload", err), identity)

  /** Decode the payload of an inbound job trigger (`POST /job/<name>`).
    *
    * Dapr delivers the job's stored data as the request body. Depending on the sidecar version and how the job was
    * scheduled, the body is either the raw JSON payload or an envelope of the form `{"data": ...}`. We try the raw form
    * first and fall back to unwrapping a top-level `data` field so both shapes work — same as the JVM twin.
    */
  private def decodeJobPayload[T](body: String, codec: JsonCodec[T]): Either[JsonDecodeException, T] =
    val json = if body.isEmpty then "null" else body
    codec.decode(json) match
      case r @ Right(_)   => r
      case Left(firstErr) =>
        try
          val env = js.JSON.parse(json)
          if (env: Any) != null && js.typeOf(env) == "object" then
            // WHAT/WHY/WHY SAFE: same documented js.Dynamic view of a JSON.parse result as in
            // decodeCallbackPayload above.
            val data = env.asInstanceOf[js.Dynamic].selectDynamic("data")
            if js.isUndefined(data) then Left(firstErr)
            else
              val inner = (data: Any) match
                case s: String => s
                case _         => js.JSON.stringify(data)
              codec.decode(inner)
          else Left(firstErr)
        catch case NonFatal(_) => Left(firstErr)

  /** Parse a CloudEvent envelope and dispatch it, mirroring the JVM `parseCloudEvent` field-for-field: absent envelope
    * fields fall back to the same defaults, a payload that fails to decode yields [[SubscriptionResult.Drop]], and a
    * malformed body throws (→ 500 → sidecar retry).
    *
    * Field reads accept only JSON strings; Jackson's `asText("")` would additionally stringify scalar non-text nodes,
    * which no CloudEvents-conformant sidecar ever sends.
    */
  private def parseCloudEvent[T](
      bodyJson: String,
      codec: JsonCodec[T],
      defaultPubsubName: PubSubName,
      defaultTopic: Topic,
      handler: CloudEvent[T] => SubscriptionResult,
  ): SubscriptionResult =
    val env = js.JSON.parse(bodyJson)
    // Jackson's readTree(...).get(name) yields null (absent) for any non-object envelope;
    // mirror that with a guarded read so a non-object JSON body means "all fields absent"
    // instead of a TypeError on property access.
    val envDyn: Option[js.Dynamic] =
      if (env: Any) == null || js.typeOf(env) != "object" then None
      else
        // WHAT/WHY/WHY SAFE: same documented js.Dynamic view of a JSON.parse result as in
        // decodeCallbackPayload above.
        Some(env.asInstanceOf[js.Dynamic])
    def rawField(name: String): js.Any =
      envDyn.fold[js.Any](js.undefined)(_.selectDynamic(name))
    def textField(name: String): Option[String] =
      (rawField(name): Any) match
        case s: String => Some(s)
        case _         => None
    val data =
      val v = rawField("data")
      // JSON.stringify(undefined) is undefined (not a string), hence the explicit guard;
      // a present-but-null data field stringifies to "null" like Jackson's NullNode.
      if js.isUndefined(v) then "null" else js.JSON.stringify(v)
    codec.decode(data) match
      case Left(_)  => SubscriptionResult.Drop
      case Right(v) =>
        handler(
          CloudEvent[T](
            id = CloudEventId(textField("id").filter(_.nonEmpty).getOrElse("unknown")),
            source = CloudEventSource(textField("source").filter(_.nonEmpty).getOrElse("unknown")),
            specVersion = CloudEventSpecVersion(textField("specversion").filter(_.nonEmpty).getOrElse("1.0")),
            eventType = CloudEventType(textField("type").filter(_.nonEmpty).getOrElse("unknown")),
            topic = textField("topic").map(Topic(_)).getOrElse(defaultTopic),
            pubSubName = textField("pubsubname").map(PubSubName(_)).getOrElse(defaultPubsubName),
            dataContentType =
              ContentType(textField("datacontenttype").filter(_.nonEmpty).getOrElse("application/json")),
            data = v,
          ),
        )
