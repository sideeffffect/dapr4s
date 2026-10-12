//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*, dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*, dapr4s.conversation.*
import io.dapr.client.{DaprClient, DaprPreviewClient}
import io.dapr.actors.client.ActorClient
import io.dapr.workflows.client.DaprWorkflowClient
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.{Level, Logger}
import scala.util.control.NonFatal

/** Concrete implementation of [[dapr4s.DaprCapability]] backed by a real [[DaprClient]].
  *
  * All interaction with the Java SDK is confined to this file and the individual `*CapabilityImpl` classes. No Java
  * types are visible in the public API.
  *
  * Lifecycle: [[dapr4s.Dapr.run]] owns all three clients; it creates them, passes them here, and closes them
  * in its `finally` block. `actorClientRef` and `workflowClientRef` start as `null` and are lazily populated on first
  * use via `AtomicReference.compareAndSet`, so [[dapr4s.Dapr.run]] can read the refs at teardown and close
  * only what was actually created.
  *
  * Marked `@scala.caps.assumeSafe` so that safe-mode user code can use [[DaprCapability]] (implemented by this class)
  * through the trait interface.
  */
@scala.caps.assumeSafe
private[dapr4s] final class DaprCapabilityImpl(
    private[internal] val client: DaprClient,
    private[internal] val clientPreview: DaprPreviewClient,
    private val actorClientRef: AtomicReference[ActorClient],
    private val workflowClientRef: AtomicReference[DaprWorkflowClient],
    // Sidecar endpoint / TLS / token overrides. Without these, the Java SDK's no-arg
    // DaprWorkflowClient / WorkflowRuntimeBuilder / ActorClient default to localhost:3500/50001 and
    // ignore the endpoints configured in DaprConfig (which breaks any non-default sidecar port).
    private[internal] val sidecarProperties: io.dapr.config.Properties,
) extends DaprCapability:

  import DaprCapabilityImpl.*

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
    new WorkflowCapabilityUniversalImpl(workflowClient).asInstanceOf[WorkflowCapability]

  def crypto: CryptoCapability^{this} =
    new CryptoCapabilityUniversalImpl(this).asInstanceOf[CryptoCapability]

  def jobs: JobsCapability^{this} =
    new JobsCapabilityUniversalImpl(this).asInstanceOf[JobsCapability]

  def conversation: ConversationCapability^{this} =
    new ConversationCapabilityUniversalImpl(this).asInstanceOf[ConversationCapability]

  /** The shared actor gRPC client, created lazily on first use (CAS so a concurrent caller's redundant client is
    * closed). Read by `Dapr.run` at teardown to close only what was created.
    */
  private[internal] def actorClient: ActorClient =
    actorClientRef.get() match
      case null =>
        val newAc = new ActorClient(sidecarProperties)
        if actorClientRef.compareAndSet(null, newAc) then newAc
        else
          // Lost the CAS race — close the redundant client and use the winner's.
          try newAc.close()
          catch
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(e)             => log.log(Level.WARNING, "Failed to close redundant Dapr client after CAS loss", e)
          actorClientRef.get().nn
      case existing => existing

  /** The shared workflow client, created lazily on first use (CAS, as `actorClient`). */
  private[internal] def workflowClient: DaprWorkflowClient =
    workflowClientRef.get() match
      case null =>
        val newWc = new DaprWorkflowClient(sidecarProperties)
        if workflowClientRef.compareAndSet(null, newWc) then newWc
        else
          // Lost the CAS race — close the redundant client and use the winner's.
          try newWc.close()
          catch
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(e)             => log.log(Level.WARNING, "Failed to close redundant Dapr client after CAS loss", e)
          workflowClientRef.get().nn
      case existing => existing

@scala.caps.assumeSafe
private object DaprCapabilityImpl:
  private val log = Logger.getLogger("dapr4s.internal.DaprCapabilityImpl")

// ---------------------------------------------------------------------------
// Universal capability implementations. Each captures the scope (or a resolved
// client), narrows to its name-bound `.Instance` impl via `instance`, and delegates
// its name-taking operations to that instance. The cast erases the capture set
// so these internal types stay package-private, exactly as the instance impls do
// (see the ^{this} note above).
// ---------------------------------------------------------------------------

@scala.caps.assumeSafe
private[internal] final class StateCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends StateCapability:
  def instance(storeName: StateStoreName): StateCapability.Instance^{this} =
    new StateCapabilityImpl(scope, storeName).asInstanceOf[StateCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, consistency: StateConsistency): Option[T] =
    instance(storeName).get(key, consistency)
  def getWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      consistency: StateConsistency,
  ): StateEntry[T] =
    instance(storeName).getWithETag(key, consistency)
  def getBulk[T: JsonCodec](storeName: StateStoreName, keys: Seq[StateStoreKey]): Map[StateStoreKey, StateEntry[T]] =
    instance(storeName).getBulk(keys)
  def queryState[T: JsonCodec](storeName: StateStoreName, query: StateQuery): List[StateEntry[T]] =
    instance(storeName).queryState(query)
  def save[T: JsonCodec](storeName: StateStoreName, key: StateStoreKey, value: T): Unit =
    instance(storeName).save(key, value)
  def saveBulk[T: JsonCodec](storeName: StateStoreName, entries: Seq[(StateStoreKey, T)]): Unit =
    instance(storeName).saveBulk(entries)
  def saveWithETag[T: JsonCodec](
      storeName: StateStoreName,
      key: StateStoreKey,
      value: T,
      etag: ETag,
      metadata: Map[MetadataKey, MetadataValue],
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    instance(storeName).saveWithETag(key, value, etag, metadata, consistency, concurrency)
  def delete(storeName: StateStoreName, key: StateStoreKey): Unit =
    instance(storeName).delete(key)
  def deleteWithETag(
      storeName: StateStoreName,
      key: StateStoreKey,
      etag: ETag,
      consistency: StateConsistency,
      concurrency: StateConcurrency,
  ): Option[ETagMismatchException] =
    instance(storeName).deleteWithETag(key, etag, consistency, concurrency)
  def transaction(storeName: StateStoreName, ops: Seq[StateOp]): Unit =
    instance(storeName).transaction(ops)

@scala.caps.assumeSafe
private[internal] final class PublishCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends PublishCapability:
  def instance(pubsubName: PubSubName): PublishCapability.Instance^{this} =
    new PublishCapabilityImpl(scope, pubsubName).asInstanceOf[PublishCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def publish[T: JsonCodec](pubsubName: PubSubName, topic: Topic, data: T): Unit =
    instance(pubsubName).publish(topic, data)
  def publishWithMetadata[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      data: T,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    instance(pubsubName).publishWithMetadata(topic, data, metadata)
  def bulkPublish[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      entries: Seq[BulkPublishEntry[T]],
  ): BulkPublishResult =
    instance(pubsubName).bulkPublish(topic, entries)

@scala.caps.assumeSafe
private[internal] final class InvokeCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends InvokeCapability:
  def instance(appId: AppId): InvokeCapability.Instance^{this} =
    new InvokeCapabilityImpl(scope, appId).asInstanceOf[InvokeCapability.Instance]
  // App-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and app id).
  def invoke[Req: JsonCodec](
      appId: AppId,
      method: InvokeMethodName,
      data: Req,
      httpMethod: HttpMethod,
      metadata: Map[MetadataKey, MetadataValue],
  )[Resp: JsonCodec]: Resp =
    instance(appId).invoke(method, data, httpMethod, metadata)[Resp]
  def invoke[Resp: JsonCodec](appId: AppId, method: InvokeMethodName): Resp =
    instance(appId).invoke[Resp](method)
  def invokeHttp(
      appId: AppId,
      method: InvokeMethodName,
      body: scala.collection.immutable.ArraySeq[Byte],
      httpMethod: HttpMethod,
      headers: Map[MetadataKey, MetadataValue],
      query: Map[QueryParamName, QueryParamValue],
  ): InvokeResponse =
    instance(appId).invokeHttp(method, body, httpMethod, headers, query)

@scala.caps.assumeSafe
private[internal] final class SecretsCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends SecretsCapability:
  def instance(storeName: SecretStoreName): SecretsCapability.Instance^{this} =
    new SecretsCapabilityImpl(scope, storeName).asInstanceOf[SecretsCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get(storeName: SecretStoreName, key: SecretKey, metadata: Map[MetadataKey, MetadataValue]): Option[SecretValue] =
    instance(storeName).get(key, metadata)
  def getBulk(storeName: SecretStoreName, metadata: Map[MetadataKey, MetadataValue]): Map[SecretKey, SecretValue] =
    instance(storeName).getBulk(metadata)

@scala.caps.assumeSafe
private[internal] final class ConfigurationCapabilityUniversalImpl(scope: DaprCapabilityImpl)
    extends ConfigurationCapability:
  def instance(storeName: ConfigurationStoreName): ConfigurationCapability.Instance^{this} =
    new ConfigurationCapabilityImpl(scope, storeName).asInstanceOf[ConfigurationCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue],
  ): Map[ConfigurationKey, ConfigurationItem] =
    instance(storeName).get(keys, metadata)
  def subscribe(
      storeName: ConfigurationStoreName,
      keys: Seq[ConfigurationKey],
      metadata: Map[MetadataKey, MetadataValue],
  )(onChange: ConfigurationUpdate => Unit): AutoCloseable^{this} =
    instance(storeName).subscribe(keys, metadata)(onChange)

@scala.caps.assumeSafe
private[internal] final class BindingsCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends BindingsCapability:
  def instance(bindingName: BindingName): BindingsCapability.Instance^{this} =
    new BindingsCapabilityImpl(scope, bindingName).asInstanceOf[BindingsCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def invoke[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
  )[Resp: JsonCodec]: Option[Resp] =
    instance(bindingName).invoke(operation, data, metadata)[Resp]
  def invokeOneWay[Req: JsonCodec](
      bindingName: BindingName,
      operation: BindingOperation,
      data: Req,
      metadata: Map[MetadataKey, MetadataValue],
  ): Unit =
    instance(bindingName).invokeOneWay(operation, data, metadata)

@scala.caps.assumeSafe
private[internal] final class LockCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends LockCapability:
  def instance(storeName: LockStoreName): LockCapability.Instance^{this} =
    new LockCapabilityImpl(scope, storeName).asInstanceOf[LockCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def tryLock(
      storeName: LockStoreName,
      resourceId: LockResourceId,
      lockOwner: LockOwner,
      expiry: scala.concurrent.duration.FiniteDuration,
  ): Boolean =
    instance(storeName).tryLock(resourceId, lockOwner, expiry)
  def unlock(storeName: LockStoreName, resourceId: LockResourceId, lockOwner: LockOwner): UnlockStatus =
    instance(storeName).unlock(resourceId, lockOwner)

@scala.caps.assumeSafe
private[internal] final class CryptoCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends CryptoCapability:
  def instance(componentName: CryptoComponentName): CryptoCapability.Instance^{this} =
    new CryptoCapabilityImpl(scope, componentName).asInstanceOf[CryptoCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def encrypt(
      componentName: CryptoComponentName,
      keyName: CryptoKeyName,
      plaintext: scala.collection.immutable.ArraySeq[Byte],
      algorithm: KeyWrapAlgorithm,
  ): scala.collection.immutable.ArraySeq[Byte] =
    instance(componentName).encrypt(keyName, plaintext, algorithm)
  def decrypt(
      componentName: CryptoComponentName,
      ciphertext: scala.collection.immutable.ArraySeq[Byte],
  ): scala.collection.immutable.ArraySeq[Byte] =
    instance(componentName).decrypt(ciphertext)

@scala.caps.assumeSafe
private[internal] final class ConversationCapabilityUniversalImpl(scope: DaprCapabilityImpl)
    extends ConversationCapability:
  def instance(componentName: ConversationComponentName): ConversationCapability.Instance^{this} =
    new ConversationCapabilityImpl(scope, componentName).asInstanceOf[ConversationCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def converse(
      componentName: ConversationComponentName,
      messages: Seq[ConversationMessage],
      tools: Seq[ConversationTool],
      toolChoice: Option[ToolChoice],
      temperature: Option[Double],
      contextId: Option[ConversationContextId],
      scrubPii: Boolean,
  ): ConversationResponse =
    instance(componentName).converse(messages, tools, toolChoice, temperature, contextId, scrubPii)

@scala.caps.assumeSafe
private[internal] final class JobsCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends JobsCapability:
  def instance(jobName: JobName): JobsCapability.Instance^{this} =
    new JobsCapabilityImpl(scope, jobName).asInstanceOf[JobsCapability.Instance]
  // Name-taking operations delegate to a freshly bound instance (cheap: the instance holds only the scope and name).
  def get(jobName: JobName): Option[JobDetails] =
    instance(jobName).get()
  def schedule[T: JsonCodec](
      jobName: JobName,
      data: T,
      schedule: JobSchedule,
      dueTime: Option[java.time.Instant],
      repeats: Option[Int],
      ttl: Option[java.time.Instant],
  ): Unit =
    instance(jobName).schedule(data, schedule, dueTime, repeats, ttl)
  def scheduleOnce[T: JsonCodec](
      jobName: JobName,
      data: T,
      dueTime: java.time.Instant,
      ttl: Option[java.time.Instant],
  ): Unit =
    instance(jobName).scheduleOnce(data, dueTime, ttl)
  def delete(jobName: JobName): Unit =
    instance(jobName).delete()

@scala.caps.assumeSafe
private[internal] final class ActorCapabilityUniversalImpl(scope: DaprCapabilityImpl) extends ActorCapability:
  def forType(actorType: ActorType): ActorCapability.Type^{this} =
    new ActorCapabilityTypeImpl(scope, actorType).asInstanceOf[ActorCapability.Type]
  // Type/id-taking operations delegate to a freshly bound type capability (cheap: it holds only the scope and type).
  def invoke[Req: JsonCodec](
      actorType: ActorType,
      actorId: ActorId,
      method: ActorMethodName,
      data: Req,
  )[Resp: JsonCodec]: Resp =
    forType(actorType).invoke(actorId, method, data)[Resp]
  def invoke[Resp: JsonCodec](actorType: ActorType, actorId: ActorId, method: ActorMethodName): Resp =
    forType(actorType).invoke[Resp](actorId, method)
  def invokeVoid(actorType: ActorType, actorId: ActorId, method: ActorMethodName): Unit =
    forType(actorType).invokeVoid(actorId, method)

@scala.caps.assumeSafe
private[internal] final class ActorCapabilityTypeImpl(scope: DaprCapabilityImpl, val actorType: ActorType)
    extends ActorCapability.Type:
  def instance(actorId: ActorId): ActorCapability.Instance^{this} =
    ActorCapabilityImpl.build(actorType, actorId, scope.actorClient).asInstanceOf[ActorCapability.Instance]
  // Id-taking operations delegate to a freshly bound instance.
  def invoke[Req: JsonCodec](actorId: ActorId, method: ActorMethodName, data: Req)[Resp: JsonCodec]: Resp =
    instance(actorId).invoke(method, data)[Resp]
  def invoke[Resp: JsonCodec](actorId: ActorId, method: ActorMethodName): Resp =
    instance(actorId).invoke[Resp](method)
  def invokeVoid(actorId: ActorId, method: ActorMethodName): Unit =
    instance(actorId).invokeVoid(method)
