package dapr.safe

import scala.util.control.NonFatal
import io.dapr.client.{DaprClient, DaprClientBuilder}
import io.dapr.actors.client.ActorClient
import io.dapr.workflows.client.DaprWorkflowClient
import java.util.concurrent.atomic.AtomicReference

/** Entry-point singleton that manages the [[DaprCapability]] lifecycle.
  *
  * This object is annotated `@scala.caps.assumeSafe` so that safe-mode user code can call `DaprRuntime.run` without
  * seeing any unsafe operations. The internal use of `DaprCapabilityImpl` (a Java-SDK-backed class) and the Java SDK
  * clients it wraps are managed entirely here.
  */
@scala.caps.assumeSafe
object DaprRuntime:

  /** Acquire a `DaprClient`, run `body` with a `DaprCapability` in context, then release the client whether `body`
    * completes normally or throws.
    *
    * Three clients are potentially created: a `DaprClient` (always), an `ActorClient` and a `DaprWorkflowClient`
    * (lazily, only when `actor()` / `workflow` are first used). All three are closed in the `finally` block in order;
    * if any close throws a non-fatal exception, it is suppressed onto the body's throwable (or rethrown standalone if
    * the body succeeded). `InterruptedException` from `DaprWorkflowClient.close()` is handled by re-interrupting the
    * current thread rather than propagating.
    *
    * ==Virtual threads==
    *
    * For best throughput, call `run` from a virtual thread (JDK 21+). Each I/O call inside the body bridges to the
    * calling thread via `CompletableFuture.get()`, which parks the virtual thread and frees its carrier platform thread
    * for other work during the wait. On a platform thread the same calls block normally — correctness is unaffected,
    * only throughput differs.
    *
    * {{{
    *   // Plain Scala / Java main():
    *   Thread.ofVirtual().start(() => DaprRuntime.run { ... }).join()
    *
    *   // Spring Boot 3.2+:  spring.threads.virtual.enabled=true
    *   // Quarkus:            @RunOnVirtualThread on the endpoint method
    *   // Helidon 4:          virtual threads by default — no annotation needed
    * }}}
    *
    * @param body
    *   a pure context function that receives a `DaprCapability`
    * @return
    *   the value returned by `body`
    */
  def run[T](body: (DaprCapability, CanThrow[Exception]) ?=> T): T =
    val client = new DaprClientBuilder().build()
    val actorClientRef = new AtomicReference[ActorClient](null)
    val workflowClientRef = new AtomicReference[DaprWorkflowClient](null)
    val impl = new internal.DaprCapabilityImpl(client, actorClientRef, workflowClientRef)
    given canThrow: CanThrow[Exception] = unsafeExceptions.canThrowAny
    var primary: Throwable | Null = null
    try body(using impl, canThrow)
    catch
      case NonFatal(t) =>
        primary = t
        throw t
    finally
      var closeEx: Throwable | Null = null
      def tryClose(autoCloseable: AutoCloseable): Unit =
        try autoCloseable.close()
        catch
          case _: InterruptedException => Thread.currentThread().interrupt()
          case NonFatal(t)             =>
            if closeEx == null then closeEx = t
            else closeEx.nn.addSuppressed(t)
      tryClose(client)
      val ac = actorClientRef.get()
      if ac != null then tryClose(ac)
      val wc = workflowClientRef.get()
      if wc != null then tryClose(wc)
      val ce = closeEx
      if ce != null then
        val p = primary
        if p != null then p.addSuppressed(ce)
        else throw ce

  /** Start an HTTP server on `appPort`, build the inbound handler set from the [[DaprApp]] returned by `body`, then
    * block until the JVM shuts down or the calling thread is interrupted.
    *
    * The Dapr sidecar discovers pub/sub subscriptions via `GET /dapr/subscribe` and delivers messages / binding events
    * / invocations via `POST /<route>`. All request handling runs on virtual threads.
    *
    * ==Usage==
    * {{{
    *   DaprRuntime.serve(appPort = 8080):
    *     val scope = summon[DaprCapability]
    *     given StateCapability  = scope.state(StoreName("statestore"))
    *     given PubSubCapability = scope.pubsub(PubSubName("pubsub"))
    *     DaprApp(
    *       subscriptions = List(
    *         Subscription[OrderEvent](PubSubName("pubsub"), Topic("orders")) { event =>
    *           // handle incoming order event
    *           SubscriptionResult.Success
    *         }
    *       ),
    *       invocations = List(
    *         InvocationRoute[OrderRequest, OrderResponse](MethodName("place-order")) { req =>
    *           // handle direct invocation
    *           OrderResponse(req.id, "processed")
    *         }
    *       )
    *     )
    * }}}
    *
    * ==Sidecar startup order==
    * Start the app (this method) before (or at the same time as) the Dapr sidecar. The sidecar calls
    * `GET /dapr/subscribe` after connecting to the app port. With Testcontainers, use `DaprContainer.withAppPort` and
    * `withAppChannelAddress` to point the sidecar at the running server.
    *
    * @param appPort
    *   the HTTP port on which the app listens (default 8080)
    * @param body
    *   a pure context function that receives a `DaprCapability` and returns a [[DaprApp]] describing all inbound
    *   handlers
    */
  def serve(appPort: Int = 8080)(body: (DaprCapability, CanThrow[Exception]) ?=> DaprApp): Unit =
    run {
      val scope = summon[DaprCapability]
      val ct = summon[CanThrow[Exception]]
      val app = body(using scope, ct)
      new internal.DaprAppServer(app).startAndBlock(appPort)
    }

  /** Run `body` with a [[DaprCapability]] pointing to a specific sidecar endpoint.
    *
    * Useful in tests (e.g. Testcontainers) where the sidecar runs on a non-default port. This avoids importing Java SDK
    * types directly.
    *
    * System properties are reset to their original values after the block completes (whether normally or
    * exceptionally).
    *
    * See [[run]] for virtual-thread usage guidance.
    */
  def runWithEndpoints[T](httpEndpoint: String, grpcEndpoint: String)(
      body: (DaprCapability, CanThrow[Exception]) ?=> T,
  ): T =
    val prevHttp = Option(System.getProperty("dapr.http.endpoint"))
    val prevGrpc = Option(System.getProperty("dapr.grpc.endpoint"))
    System.setProperty("dapr.http.endpoint", httpEndpoint)
    System.setProperty("dapr.grpc.endpoint", grpcEndpoint)
    try run(body)
    finally
      prevHttp match
        case Some(v) => System.setProperty("dapr.http.endpoint", v)
        case None    => System.clearProperty("dapr.http.endpoint")
      prevGrpc match
        case Some(v) => System.setProperty("dapr.grpc.endpoint", v)
        case None    => System.clearProperty("dapr.grpc.endpoint")
