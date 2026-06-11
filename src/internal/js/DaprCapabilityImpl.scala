//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import java.net.URI
import scala.scalajs.js

/** Lazily-created-client holder, the JS twin of the `AtomicReference[Client]` pattern in the JVM `Dapr.run` /
  * `DaprCapabilityImpl`: [[dapr4s.Dapr.run]] owns the holder, [[DaprCapabilityImpl]] populates it on first use, and
  * `run`'s `finally` block reads [[created]] to close only what was actually created.
  *
  * A plain `var` replaces the JVM's `AtomicReference.compareAndSet`: JavaScript is single-threaded, and
  * [[getOrCreate]] performs no `js.await` between reading and writing the field — JSPI can only interleave other
  * work at suspension points, so the read-check-write below is atomic by construction and there is no CAS race
  * (hence also no "close the redundant client" branch the JVM needs).
  */
@scala.caps.assumeSafe
private[dapr4s] final class LazyClientRef[A]:
  private var ref: Option[A] = None

  /** The client, if one was created. */
  def created: Option[A] = ref

  /** Return the already-created client or create, store, and return a new one. */
  def getOrCreate(create: () => A): A =
    ref match
      case Some(existing) => existing
      case None           =>
        val client = create()
        ref = Some(client)
        client

/** Concrete implementation of [[dapr4s.DaprCapability]] backed by the Dapr JS SDK (`@dapr/dapr`) — the Scala.js twin
  * of the JVM `DaprCapabilityImpl`.
  *
  * All interaction with the JS SDK is confined to this file, the individual `*CapabilityImpl` classes, and the
  * facades in `dapr4s.internal.facade`. No JS types are visible in the public API.
  *
  * Lifecycle: [[dapr4s.Dapr.run]] owns all three clients; it creates the HTTP-protocol [[facade.DaprClient]] and the
  * two lazy refs, passes them here, and stops them in its `finally` block. The gRPC client (configuration and crypto
  * are gRPC-only in the JS SDK) and the [[facade.DaprWorkflowClient]] (gRPC, vendored durabletask) are created on
  * first use via [[LazyClientRef.getOrCreate]], so `run` can close only what was actually created.
  *
  * Marked `@scala.caps.assumeSafe` so that safe-mode user code can use [[DaprCapability]] (implemented by this class)
  * through the trait interface.
  */
@scala.caps.assumeSafe
private[dapr4s] final class DaprCapabilityImpl(
    private[internal] val client: facade.DaprClient,
    private[internal] val sidecar: SidecarConfig,
    private val grpcClientRef: LazyClientRef[facade.DaprClient],
    private val workflowClientRef: LazyClientRef[facade.DaprWorkflowClient],
) extends DaprCapability:

  import DaprCapabilityImpl.*

  /** The gRPC-protocol client, created on first use — required by `configuration` and `crypto`, whose HTTP
    * implementations in the JS SDK throw `HTTPNotSupportedError`.
    */
  private[internal] def grpcClient: facade.DaprClient =
    grpcClientRef.getOrCreate(() => new facade.DaprClient(grpcClientOptions(sidecar)))

  // WHY ^{this}: sub-capabilities extend ExclusiveCapability, so CC infers ^{fresh} for new
  // instances. The trait declares ^{this} to prevent sub-capabilities from outliving `this`.
  // Explicit ^{this} here overrides the ^{fresh} inference and satisfies the override check.
  // The asInstanceOf cast then erases the capture set so internal Impl types stay package-private.

  def state(storeName: StateStoreName): StateCapability^{this} =
    new StateCapabilityImpl(this, storeName).asInstanceOf[StateCapability]

  def publish(pubsubName: PubSubName): PublishCapability^{this} =
    new PublishCapabilityImpl(this, pubsubName).asInstanceOf[PublishCapability]

  def invoke: InvokeCapability^{this} =
    new InvokeCapabilityImpl(this).asInstanceOf[InvokeCapability]

  def secrets(storeName: SecretStoreName): SecretsCapability^{this} =
    new SecretsCapabilityImpl(this, storeName).asInstanceOf[SecretsCapability]

  def configuration(storeName: ConfigurationStoreName): ConfigurationCapability^{this} =
    new ConfigurationCapabilityImpl(this, storeName).asInstanceOf[ConfigurationCapability]

  def bindings(bindingName: BindingName): BindingsCapability^{this} =
    new BindingsCapabilityImpl(this, bindingName).asInstanceOf[BindingsCapability]

  def lock(storeName: LockStoreName): LockCapability^{this} =
    new LockCapabilityImpl(this, storeName).asInstanceOf[LockCapability]

  def actor(actorType: ActorType, actorId: ActorId): ActorCapability^{this} =
    new ActorCapabilityImpl(actorType, actorId, sidecar).asInstanceOf[ActorCapability]

  def workflow: WorkflowCapability^{this} =
    val wc = workflowClientRef.getOrCreate(() => new facade.DaprWorkflowClient(workflowClientOptions(sidecar)))
    new WorkflowCapabilityImpl(wc).asInstanceOf[WorkflowCapability]

  def crypto(componentName: CryptoComponentName): CryptoCapability^{this} =
    new CryptoCapabilityImpl(this, componentName).asInstanceOf[CryptoCapability]

  def jobs: JobsCapability^{this} =
    throw new UnsupportedOperationException(
      "the Dapr JS SDK (@dapr/dapr 3.x) has no jobs API; use dapr4s on the JVM for the jobs capability",
    )

  def conversation(componentName: ConversationComponentName): ConversationCapability^{this} =
    throw new UnsupportedOperationException(
      "the Dapr JS SDK (@dapr/dapr 3.x) has no conversation API; use dapr4s on the JVM for the conversation capability",
    )

@scala.caps.assumeSafe
private[dapr4s] object DaprCapabilityImpl:

  /** Split a `SidecarConfig` endpoint URI into the `(daprHost, daprPort)` string pair the JS SDK options take.
    *
    * The SDK reassembles them as `"daprHost:daprPort"` and parses the result with its `HttpEndpoint` /
    * `GrpcEndpoint` network classes, both of which accept a scheme inside the host part — so the URI scheme is kept
    * (`http://host` + port) to preserve TLS/plaintext selection. For gRPC clients the scheme is translated to the
    * SDK's preferred `grpc`/`grpcs` (passing `http`/`https` works but triggers a deprecation `console.warn` in
    * `GrpcEndpoint.setScheme`). A URI without an explicit port falls back to the parser defaults: 80/443 by scheme
    * for HTTP (`HttpEndpoint`), 443 for gRPC (`URIParseConfig.DEFAULT_PORT`).
    */
  private def hostAndPort(endpoint: URI, forGrpc: Boolean): (String, String) =
    val rawScheme = endpoint.getScheme match
      case null => "http"
      case s    => s
    val scheme =
      if forGrpc then
        rawScheme match
          case "http"  => "grpc"
          case "https" => "grpcs"
          case other   => other
      else rawScheme
    val host = endpoint.getHost match
      case null => "localhost"
      case h    => h
    val port = endpoint.getPort match
      case -1 if forGrpc            => 443
      case -1 if rawScheme == "https" => 443
      case -1                       => 80
      case p                        => p
    (s"$scheme://$host", port.toString)

  private def undefOr[A](o: Option[A]): js.UndefOr[A] =
    o.fold[js.UndefOr[A]](js.undefined)(a => a)

  /** Options for the default HTTP-protocol client, from `SidecarConfig.httpEndpoint`.
    *
    * Only the knobs the JS SDK exposes are mapped: endpoint, API token, and max body size (from
    * `grpcMaxInboundMessageSizeBytes`, the closest dapr4s knob — the SDK applies `maxBodySizeMb` to both protocols).
    * The remaining `SidecarConfig` fields are OkHttp/gRPC-Java transport settings with no JS equivalent and are
    * ignored here (documented on [[dapr4s.Dapr]]).
    */
  private[dapr4s] def httpClientOptions(sc: SidecarConfig): facade.DaprClientOptions =
    val (host, port) = hostAndPort(sc.httpEndpoint, forGrpc = false)
    new facade.DaprClientOptions(
      daprHost = host,
      daprPort = port,
      communicationProtocol = facade.CommunicationProtocolEnum.HTTP,
      daprApiToken = undefOr(sc.apiToken.map(_.value)),
      maxBodySizeMb = sc.grpcMaxInboundMessageSizeBytes.toDouble / (1024 * 1024),
    )

  /** Options for the lazy gRPC-protocol client, from `SidecarConfig.grpcEndpoint`. */
  private[internal] def grpcClientOptions(sc: SidecarConfig): facade.DaprClientOptions =
    val (host, port) = hostAndPort(sc.grpcEndpoint, forGrpc = true)
    new facade.DaprClientOptions(
      daprHost = host,
      daprPort = port,
      communicationProtocol = facade.CommunicationProtocolEnum.GRPC,
      daprApiToken = undefOr(sc.apiToken.map(_.value)),
      maxBodySizeMb = sc.grpcMaxInboundMessageSizeBytes.toDouble / (1024 * 1024),
    )

  /** Options for the lazy workflow client (gRPC, vendored durabletask), from `SidecarConfig.grpcEndpoint`. */
  private[internal] def workflowClientOptions(sc: SidecarConfig): facade.WorkflowClientOptions =
    val (host, port) = hostAndPort(sc.grpcEndpoint, forGrpc = true)
    new facade.WorkflowClientOptions(
      daprHost = host,
      daprPort = port,
      daprApiToken = undefOr(sc.apiToken.map(_.value)),
    )
