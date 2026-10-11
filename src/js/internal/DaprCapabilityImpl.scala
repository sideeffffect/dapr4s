//> using target.platform "scala-js"
package dapr4s.internal

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*, dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*
import java.net.URI
import dapr4styped.daprDapr.anon.{PartialDaprClientOptions, PartialWorkflowClientOpti}
import dapr4styped.daprDapr.mod.{CommunicationProtocolEnum, DaprClient, DaprWorkflowClient}

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
  * ScalablyTyped-generated `dapr4styped.daprDapr` facades (see js-deps.scala). No JS types are visible in the public API.
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

  def state: StateCapability^{this} =
    new StateCapabilityUniversalImpl(this).asInstanceOf[StateCapability]

  def publish: PublishCapability^{this} =
    new PublishCapabilityUniversalImpl(this).asInstanceOf[PublishCapability]

  def invoke: InvokeCapability^{this} =
    new InvokeCapabilityUniversalImpl(this).asInstanceOf[InvokeCapability]

  def secrets: SecretsCapability^{this} =
    new SecretsCapabilityUniversalImpl(this).asInstanceOf[SecretsCapability]

  def configuration: ConfigurationCapability^{this} =
    new ConfigurationCapabilityUniversalImpl(this).asInstanceOf[ConfigurationCapability]

  def bindings: BindingsCapability^{this} =
    new BindingsCapabilityUniversalImpl(this).asInstanceOf[BindingsCapability]

  def lock: LockCapability^{this} =
    new LockCapabilityUniversalImpl(this).asInstanceOf[LockCapability]

  def actor: ActorCapability^{this} =
    new ActorCapabilityUniversalImpl(this).asInstanceOf[ActorCapability]

  def workflow: WorkflowCapability^{this} =
    val wc = workflowClientRef.getOrCreate(() => new DaprWorkflowClient(workflowClientOptions(sidecar)))
    new WorkflowCapabilityUniversalImpl(wc).asInstanceOf[WorkflowCapability]

  def crypto: CryptoCapability^{this} =
    new CryptoCapabilityUniversalImpl(this).asInstanceOf[CryptoCapability]

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

// ---------------------------------------------------------------------------
// Universal capability implementations — the Scala.js twins of the JVM
// *CapabilityUniversalImpl classes. Each narrows to its name-bound `.Instance`
// impl via `apply`, and its name-taking operations delegate to that instance;
// the cast erases the capture set so these internal types stay package-private.
// (No jobs/conversation: the Dapr JS SDK has neither.)
// ---------------------------------------------------------------------------

@scala.caps.assumeSafe
private[internal] final class StateCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends StateCapability:
  def apply(storeName: StateStoreName): StateCapability.Instance^{this} =
    new StateCapabilityImpl(scope, storeName).asInstanceOf[StateCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, consistency: StateConsistency): Option[T] =
    apply(storeName).get(key, consistency)
  def getWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      consistency: StateConsistency,
  ): StateEntry[T] =
    apply(storeName).getWithETag(key, consistency)
  def getBulk[T: JsonCodec](storeName: StateStoreName, keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]] =
    apply(storeName).getBulk(keys)
  def queryState[T: JsonCodec](storeName: StateStoreName, query: StateQuery): List[StateEntry[T]] =
    apply(storeName).queryState(query)
  def save[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, value: T): Unit =
    apply(storeName).save(key, value)
  def saveBulk[T: JsonCodec](storeName: StateStoreName, entries: Seq[(StateStoreKey, T)]): Unit =
    apply(storeName).saveBulk(entries)
  def saveWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue],
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    apply(storeName).saveWithETag(key, value, etag, metadata, consistency, concurrency)
  def delete(storeName: StateStoreName, key: StateStoreKey): Unit =
    apply(storeName).delete(key)
  def deleteWithETag(
      storeName: StateStoreName,
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    apply(storeName).deleteWithETag(key, etag, consistency, concurrency)
  def transaction(storeName: StateStoreName, ops: Seq[StateOp]): Unit =
    apply(storeName).transaction(ops)

@scala.caps.assumeSafe
private[internal] final class PublishCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends PublishCapability:
  def apply(pubsubName: PubSubName): PublishCapability.Instance^{this} =
    new PublishCapabilityImpl(scope, pubsubName).asInstanceOf[PublishCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def publish[T: JsonCodec](pubsubName: PubSubName, topic: Topic, data: T): Unit =
    apply(pubsubName).publish(topic, data)
  def publishWithMetadata[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    apply(pubsubName).publishWithMetadata(topic, data, metadata)
  def bulkPublish[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      entries: Seq[BulkPublishEntry[T]],
  ): BulkPublishResult =
    apply(pubsubName).bulkPublish(topic, entries)

@scala.caps.assumeSafe
private[internal] final class InvokeCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends InvokeCapability:
  def apply(appId: AppId): InvokeCapability.Instance^{this} =
    new InvokeCapabilityImpl(scope, appId).asInstanceOf[InvokeCapability.Instance]
  // App-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and app id).
  def invoke[Req: JsonCodec](
      appId: AppId,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod,
      metadata: Map[MetadataKey, MetadataValue],
  )[Resp: JsonCodec]: Resp =
    apply(appId).invoke(method, data, httpMethod, metadata)[Resp]
  def invoke[Resp: JsonCodec](appId: AppId, method: InvokeMethodName): Resp =
    apply(appId).invoke[Resp](method)

@scala.caps.assumeSafe
private[internal] final class SecretsCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends SecretsCapability:
  def apply(storeName: SecretStoreName): SecretsCapability.Instance^{this} =
    new SecretsCapabilityImpl(scope, storeName).asInstanceOf[SecretsCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get(storeName: SecretStoreName, key: SecretKey, metadata: Map[MetadataKey, MetadataValue]): Option[SecretValue] =
    apply(storeName).get(key, metadata)
  def getBulk(storeName: SecretStoreName, metadata: Map[MetadataKey, MetadataValue]): Map[SecretKey, SecretValue] =
    apply(storeName).getBulk(metadata)

@scala.caps.assumeSafe
private[internal] final class ConfigurationCapabilityUniversalImpl(scope: DaprCapabilityImpl)
    extends ConfigurationCapability:
  def apply(storeName: ConfigurationStoreName): ConfigurationCapability.Instance^{this} =
    new ConfigurationCapabilityImpl(scope, storeName).asInstanceOf[ConfigurationCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue],
  ): Map[ConfigurationKey, ConfigurationItem] =
    apply(storeName).get(keys, metadata)
  def subscribe(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue],
  )(onChange: ConfigurationUpdate => Unit): AutoCloseable^{this} =
    apply(storeName).subscribe(keys, metadata)(onChange)

@scala.caps.assumeSafe
private[internal] final class BindingsCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends BindingsCapability:
  def apply(bindingName: BindingName): BindingsCapability.Instance^{this} =
    new BindingsCapabilityImpl(scope, bindingName).asInstanceOf[BindingsCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def invoke[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
  )[Resp: JsonCodec]: Option[Resp] =
    apply(bindingName).invoke(operation, data, metadata)[Resp]
  def invokeOneWay[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    apply(bindingName).invokeOneWay(operation, data, metadata)

@scala.caps.assumeSafe
private[internal] final class LockCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends LockCapability:
  def apply(storeName: LockStoreName): LockCapability.Instance^{this} =
    new LockCapabilityImpl(scope, storeName).asInstanceOf[LockCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def tryLock(
      storeName: LockStoreName,
      resourceId: LockResourceId,
      lockOwner: LockOwner,
      expiry: scala.concurrent.duration.FiniteDuration,
  ): Boolean =
    apply(storeName).tryLock(resourceId, lockOwner, expiry)
  def unlock(storeName: LockStoreName, resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    apply(storeName).unlock(resourceId, lockOwner)

@scala.caps.assumeSafe
private[internal] final class CryptoCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends CryptoCapability:
  def apply(componentName: CryptoComponentName): CryptoCapability.Instance^{this} =
    new CryptoCapabilityImpl(scope, componentName).asInstanceOf[CryptoCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def encrypt(
      componentName: CryptoComponentName,
      keyName: CryptoKeyName,
      plaintext: scala.collection.immutable.ArraySeq[Byte],
      algorithm: KeyWrapAlgorithm,
  ): scala.collection.immutable.ArraySeq[Byte] =
    apply(componentName).encrypt(keyName, plaintext, algorithm)
  def decrypt(
      componentName: CryptoComponentName,
      ciphertext: scala.collection.immutable.ArraySeq[Byte],
  ): scala.collection.immutable.ArraySeq[Byte] =
    apply(componentName).decrypt(ciphertext)

@scala.caps.assumeSafe
private[internal] final class ActorCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends ActorCapability:
  def apply(actorType: ActorType): ActorCapability.Type^{this} =
    new ActorCapabilityTypeImpl(scope, actorType).asInstanceOf[ActorCapability.Type]
  // Type/id-taking operations delegate to a freshly bound type capability (cheap: it holds only the scope and type).
  def invoke[Req: JsonCodec](
      actorType: ActorType,
      actorId: ActorId,
      method: ActorMethodName,
      data: Req,
  )[Resp: JsonCodec]: Resp =
    apply(actorType).invoke(actorId, method, data)[Resp]
  def invoke[Resp: JsonCodec](actorType: ActorType, actorId: ActorId, method: ActorMethodName): Resp =
    apply(actorType).invoke[Resp](actorId, method)
  def invokeVoid(actorType: ActorType, actorId: ActorId, method: ActorMethodName): Unit =
    apply(actorType).invokeVoid(actorId, method)

@scala.caps.assumeSafe
private[internal] final class ActorCapabilityTypeImpl(scope: DaprCapabilityImpl, val actorType: ActorType)
    extends ActorCapability.Type:
  def apply(actorId: ActorId): ActorCapability.Instance^{this} =
    new ActorCapabilityImpl(actorType, actorId, scope.sidecar).asInstanceOf[ActorCapability.Instance]
  // Id-taking operations delegate to a freshly bound instance.
  def invoke[Req: JsonCodec](actorId: ActorId, method: ActorMethodName, data: Req)[Resp: JsonCodec]: Resp =
    apply(actorId).invoke(method, data)[Resp]
  def invoke[Resp: JsonCodec](actorId: ActorId, method: ActorMethodName): Resp =
    apply(actorId).invoke[Resp](method)
  def invokeVoid(actorId: ActorId, method: ActorMethodName): Unit =
    apply(actorId).invokeVoid(method)
