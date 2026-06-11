//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import java.net.URI
import typings.daprDapr.anon.{PartialDaprClientOptions, PartialWorkflowClientOpti}
import typings.daprDapr.mod.{CommunicationProtocolEnum, DaprClient, DaprWorkflowClient}

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
  * All interaction with the JS SDK is confined to this file and the individual `*CapabilityImpl` classes, through the
  * ScalablyTyped-generated `typings.daprDapr` facades (see js-deps.scala). No JS types are visible in the public API.
  *
  * Lifecycle: [[dapr4s.Dapr.run]] owns all three clients; it creates the HTTP-protocol [[DaprClient]] and the two
  * lazy refs, passes them here, and stops them in its `finally` block. The gRPC client (configuration and crypto are
  * gRPC-only in the JS SDK) and the [[DaprWorkflowClient]] (gRPC, vendored durabletask) are created on first use via
  * [[LazyClientRef.getOrCreate]], so `run` can close only what was actually created.
  *
  * Marked `@scala.caps.assumeSafe` so that safe-mode user code can use [[DaprCapability]] (implemented by this class)
  * through the trait interface.
  */
@scala.caps.assumeSafe
private[dapr4s] final class DaprCapabilityImpl(
    private[internal] val client: DaprClient,
    private[internal] val sidecar: SidecarConfig,
    private val grpcClientRef: LazyClientRef[DaprClient],
    private val workflowClientRef: LazyClientRef[DaprWorkflowClient],
) extends DaprCapability:

  import DaprCapabilityImpl.*

  /** The gRPC-protocol client, created on first use — required by `configuration` and `crypto`, whose HTTP
    * implementations in the JS SDK throw `HTTPNotSupportedError`.
    */
  private[internal] def grpcClient: DaprClient =
    grpcClientRef.getOrCreate(() => new DaprClient(grpcClientOptions(sidecar)))

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
    val wc = workflowClientRef.getOrCreate(() => new DaprWorkflowClient(workflowClientOptions(sidecar)))
    new WorkflowCapabilityImpl(wc).asInstanceOf[WorkflowCapability]

  def crypto(componentName: CryptoComponentName): CryptoCapability^{this} =
    new CryptoCapabilityImpl(this, componentName).asInstanceOf[CryptoCapability]

  // No jobs/conversation here: the Dapr JS SDK (@dapr/dapr 3.x) has no jobs or conversation API,
  // so those factory methods exist only on the JVM DaprCapabilityPlatform parent trait — on this
  // platform they are absent from DaprCapability at compile time (no runtime throw needed).

@scala.caps.assumeSafe
private[dapr4s] object DaprCapabilityImpl:

  /** Split a `SidecarConfig` endpoint URI into the `(daprHost, daprPort)` string pair the JS SDK options take.
    *
    * The SDK reassembles them as `"daprHost:daprPort"` and parses the result with its `HttpEndpoint` /
    * `GrpcEndpoint` network classes, both of which accept a scheme inside the host part — so for HTTP the URI scheme
    * is kept (`http://host` + port) to preserve TLS/plaintext selection.
    *
    * For gRPC the scheme mapping is dictated by a quirk verified against a live sidecar: the workflow stack
    * (`TaskHubGrpcWorker`/`TaskHubGrpcClient`) passes `GrpcEndpoint.endpoint` to grpc-js as the raw channel target,
    * and `GrpcEndpoint.setEndpoint` renders non-`dns` schemes as `"<scheme>:host:port"` — a target grpc-js cannot
    * resolve for `grpc`/`grpcs` (it falls back to `dns:grpc:host:port`, i.e. "Name resolution failed"). `grpcs` also
    * never sets `tls` (`setTls` only honours `https:` or `?tls=true`). The only scheme spellings that work across
    * '''both''' gRPC consumers (the workflow stack and `GRPCClient`, which reads just hostname/port/tls) are:
    *   - plaintext → '''no scheme''' (bare host): `GrpcEndpoint` then applies its default `dns` scheme, yielding the
    *     `"dns:host:port"` target grpc-js expects;
    *   - TLS → `https://host`: the one spelling that sets `tls = true` and still coerces the scheme to `dns`
    *     (`setScheme` logs a one-time deprecation `console.warn`, the price of the only working TLS form).
    *
    * A URI without an explicit port falls back to the parser defaults: 80/443 by scheme for HTTP (`HttpEndpoint`),
    * 443 for gRPC (`URIParseConfig.DEFAULT_PORT`).
    */
  private def hostAndPort(endpoint: URI, forGrpc: Boolean): (String, String) =
    val rawScheme = endpoint.getScheme match
      case null => "http"
      case s    => s
    val scheme =
      if forGrpc then
        rawScheme match
          case "http" | "grpc"   => "" // bare host → GrpcEndpoint's default "dns" scheme (see scaladoc)
          case "https" | "grpcs" => "https" // the only spelling that both sets tls and resolves (see scaladoc)
          case other             => other
      else rawScheme
    val host = endpoint.getHost match
      case null => "localhost"
      case h    => h
    val port = endpoint.getPort match
      case -1 if forGrpc            => 443
      case -1 if rawScheme == "https" => 443
      case -1                       => 80
      case p                        => p
    val hostPart = if scheme.isEmpty then host else s"$scheme://$host"
    (hostPart, port.toString)

  /** Options for the default HTTP-protocol client, from `SidecarConfig.httpEndpoint`.
    *
    * Only the knobs the JS SDK exposes are mapped: endpoint, API token, and max body size (from
    * `grpcMaxInboundMessageSizeBytes`, the closest dapr4s knob — the SDK applies `maxBodySizeMb` to both protocols).
    * The remaining `SidecarConfig` fields are OkHttp/gRPC-Java transport settings with no JS equivalent and are
    * ignored here (documented on [[dapr4s.Dapr]]).
    */
  private[dapr4s] def httpClientOptions(sc: SidecarConfig): PartialDaprClientOptions =
    val (host, port) = hostAndPort(sc.httpEndpoint, forGrpc = false)
    val options = PartialDaprClientOptions()
      .setDaprHost(host)
      .setDaprPort(port)
      .setCommunicationProtocol(CommunicationProtocolEnum.HTTP)
      .setMaxBodySizeMb(sc.grpcMaxInboundMessageSizeBytes.toDouble / (1024 * 1024))
    sc.apiToken.foreach(t => options.setDaprApiToken(t.value): Unit)
    options

  /** Options for the lazy gRPC-protocol client, from `SidecarConfig.grpcEndpoint`. */
  private[internal] def grpcClientOptions(sc: SidecarConfig): PartialDaprClientOptions =
    val (host, port) = hostAndPort(sc.grpcEndpoint, forGrpc = true)
    val options = PartialDaprClientOptions()
      .setDaprHost(host)
      .setDaprPort(port)
      .setCommunicationProtocol(CommunicationProtocolEnum.GRPC)
      .setMaxBodySizeMb(sc.grpcMaxInboundMessageSizeBytes.toDouble / (1024 * 1024))
    sc.apiToken.foreach(t => options.setDaprApiToken(t.value): Unit)
    options

  /** Options for the lazy workflow client (gRPC, vendored durabletask), from `SidecarConfig.grpcEndpoint`. */
  private[internal] def workflowClientOptions(sc: SidecarConfig): PartialWorkflowClientOpti =
    val (host, port) = hostAndPort(sc.grpcEndpoint, forGrpc = true)
    val options = PartialWorkflowClientOpti().setDaprHost(host).setDaprPort(port)
    sc.apiToken.foreach(t => options.setDaprApiToken(t.value): Unit)
    options
