package dapr4s

import scala.util.control.NonFatal
import io.dapr.client.{DaprClient, DaprClientBuilder, DaprPreviewClient}
import io.dapr.config.Properties
import io.dapr.actors.client.ActorClient
import io.dapr.workflows.client.DaprWorkflowClient
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/** Entry point that manages the [[DaprCapability]] lifecycle.
  *
  * Construct with a [[DaprConfig]] (defaults to sensible local-sidecar settings) and call `run` or `serve`:
  *
  * {{{
  *   // one-shot request/response:
  *   Dapr().run:
  *     summon[DaprCapability].state(StateStoreName("statestore")).get(StateStoreKey("k"))
  *
  *   // long-running HTTP server:
  *   Dapr(config).serve:
  *     DaprApp(subscriptions = ..., invocations = ...)
  * }}}
  *
  * Annotated `@scala.caps.assumeSafe` so that safe-mode user code can call `Dapr(config).run` without seeing any unsafe
  * operations. The internal use of `DaprCapabilityImpl` (a Java-SDK-backed class) and the Java SDK clients it wraps are
  * managed entirely here.
  */
@scala.caps.assumeSafe
class Dapr(config: DaprConfig = DaprConfig()):

  /** gRPC/TLS overrides for the workflow client and runtime, derived from [[config]].
    *
    * The Java SDK's `DaprWorkflowClient()` and `WorkflowRuntimeBuilder()` no-arg constructors default to
    * `localhost:50001` and ignore any configured gRPC endpoint. Passing these properties makes both honour
    * `config.sidecar.grpcEndpoint` (and the related TLS / API-token settings), so workflows work against a sidecar on a
    * non-default port.
    */
  private def workflowProperties: Properties =
    val sc = config.sidecar
    val overrides = new java.util.HashMap[String, String]()
    overrides.put(Properties.GRPC_ENDPOINT.getName, sc.grpcEndpoint.toString)
    overrides.put(Properties.GRPC_TLS_INSECURE.getName, sc.grpcTlsInsecure.toString)
    sc.apiToken.foreach(t => overrides.put(Properties.API_TOKEN.getName, t.value))
    sc.grpcTlsCertPath.foreach(p => overrides.put(Properties.GRPC_TLS_CERT_PATH.getName, p.toString))
    sc.grpcTlsKeyPath.foreach(p => overrides.put(Properties.GRPC_TLS_KEY_PATH.getName, p.toString))
    sc.grpcTlsCaPath.foreach(p => overrides.put(Properties.GRPC_TLS_CA_PATH.getName, p.toString))
    new Properties(overrides)

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
    *   Thread.ofVirtual().start(() => Dapr().run { ... }).join()
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
  def run[T](body: DaprCapability ?=> T): T =
    val sc = config.sidecar
    val builder = new DaprClientBuilder()
    builder
      .withPropertyOverride(Properties.HTTP_ENDPOINT, sc.httpEndpoint.toString)
      .withPropertyOverride(Properties.GRPC_ENDPOINT, sc.grpcEndpoint.toString)
      .withPropertyOverride(Properties.HTTP_CLIENT_READ_TIMEOUT_SECONDS, sc.httpClientReadTimeout.toSeconds.toString)
      .withPropertyOverride(Properties.HTTP_CLIENT_MAX_REQUESTS, sc.httpClientMaxRequests.toString)
      .withPropertyOverride(Properties.HTTP_CLIENT_MAX_IDLE_CONNECTIONS, sc.httpClientMaxIdleConnections.toString)
      .withPropertyOverride(
        Properties.GRPC_MAX_INBOUND_MESSAGE_SIZE_BYTES,
        sc.grpcMaxInboundMessageSizeBytes.toString,
      )
      .withPropertyOverride(
        Properties.GRPC_MAX_INBOUND_METADATA_SIZE_BYTES,
        sc.grpcMaxInboundMetadataSizeBytes.toString,
      )
      .withPropertyOverride(Properties.GRPC_ENABLE_KEEP_ALIVE, sc.grpcEnableKeepAlive.toString)
      .withPropertyOverride(Properties.GRPC_KEEP_ALIVE_TIME_SECONDS, sc.grpcKeepAliveTime.toSeconds.toString)
      .withPropertyOverride(Properties.GRPC_KEEP_ALIVE_TIMEOUT_SECONDS, sc.grpcKeepAliveTimeout.toSeconds.toString)
      .withPropertyOverride(Properties.GRPC_KEEP_ALIVE_WITHOUT_CALLS, sc.grpcKeepAliveWithoutCalls.toString)
      .withPropertyOverride(Properties.GRPC_TLS_INSECURE, sc.grpcTlsInsecure.toString)
      .withPropertyOverride(Properties.MAX_RETRIES, sc.maxRetries.toString)
      .withPropertyOverride(Properties.TIMEOUT, sc.timeout.toSeconds.toString)
    sc.apiToken.foreach(t => builder.withPropertyOverride(Properties.API_TOKEN, t.value))
    sc.grpcTlsCertPath.foreach(p => builder.withPropertyOverride(Properties.GRPC_TLS_CERT_PATH, p.toString))
    sc.grpcTlsKeyPath.foreach(p => builder.withPropertyOverride(Properties.GRPC_TLS_KEY_PATH, p.toString))
    sc.grpcTlsCaPath.foreach(p => builder.withPropertyOverride(Properties.GRPC_TLS_CA_PATH, p.toString))
    // AbstractDaprClient (the concrete type DaprClientBuilder.build() returns) implements both
    // DaprClient and DaprPreviewClient; clientPreview is the same instance viewed through the
    // preview API, so only `client` is closed below.
    val client = builder.build()
    val clientPreview = client.asInstanceOf[DaprPreviewClient]
    val actorClientRef = new AtomicReference[ActorClient](null)
    val workflowClientRef = new AtomicReference[DaprWorkflowClient](null)
    val impl =
      new internal.DaprCapabilityImpl(client, clientPreview, actorClientRef, workflowClientRef, workflowProperties)
    var primary: Throwable | Null = null
    try body(using impl)
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

  /** Start an HTTP server on `config.appServer.port`, build the inbound handler set from the [[DaprApp]] returned by
    * `body`, then block until the JVM shuts down or the calling thread is interrupted.
    *
    * The Dapr sidecar discovers pub/sub subscriptions via `GET /dapr/subscribe` and delivers messages / binding events
    * / invocations via `POST /<route>`. All request handling runs on virtual threads.
    *
    * ==Usage==
    * {{{
    *   Dapr(config).serve:
    *     val scope = summon[DaprCapability]
    *     given StateCapability  = scope.state(StateStoreName("statestore"))
    *     given PubSubCapability = scope.pubsub(PubSubName("pubsub"))
    *     DaprApp(
    *       subscriptions = List(
    *         Subscription[OrderEvent](PubSubName("pubsub"), Topic("orders")) { event =>
    *           // handle incoming order event
    *           SubscriptionResult.Success
    *         }
    *       ),
    *       invocations = List(
    *         InvocationRoute[OrderRequest, OrderResponse](InvocationMethodName("place-order")) { req =>
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
    * @param body
    *   a pure context function that receives a `DaprCapability` and returns a [[DaprApp]] describing all inbound
    *   handlers
    */
  def serve(body: DaprCapability ?=> DaprApp): Nothing =
    run:
      val cap = summon[DaprCapability]
      // Fail fast on structural misconfiguration (duplicate/colliding handlers) before binding the port.
      val app = body.validateOrThrow()
      new internal.DaprAppServer(app).startAndBlock(
        port = config.appServer.port.value,
        daprCapability = cap,
        sidecarHttpEndpoint = () => config.sidecar.httpEndpoint,
        workflowProperties = workflowProperties,
        shutdownGrace = config.appServer.shutdownGrace,
        httpBacklog = config.appServer.httpBacklog,
        actorConfig = config.actors,
      )
