//> using target.platform "jvm"
package dapr4s.internal

import dapr4s.*
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

  def state: AccessStateCapability^{this} =
    new AccessStateCapabilityImpl(this).asInstanceOf[AccessStateCapability]

  def publish: AccessPublishCapability^{this} =
    new AccessPublishCapabilityImpl(this).asInstanceOf[AccessPublishCapability]

  def invoke: AccessInvokeCapability^{this} =
    new AccessInvokeCapabilityImpl(this).asInstanceOf[AccessInvokeCapability]

  def secrets: AccessSecretsCapability^{this} =
    new AccessSecretsCapabilityImpl(this).asInstanceOf[AccessSecretsCapability]

  def configuration: AccessConfigurationCapability^{this} =
    new AccessConfigurationCapabilityImpl(this).asInstanceOf[AccessConfigurationCapability]

  def bindings: AccessBindingsCapability^{this} =
    new AccessBindingsCapabilityImpl(this).asInstanceOf[AccessBindingsCapability]

  def lock: AccessLockCapability^{this} =
    new AccessLockCapabilityImpl(this).asInstanceOf[AccessLockCapability]

  def actor: AccessActorCapability^{this} =
    new AccessActorCapabilityImpl(this).asInstanceOf[AccessActorCapability]

  def workflow: AccessWorkflowCapability^{this} =
    new AccessWorkflowCapabilityImpl(workflowClient).asInstanceOf[AccessWorkflowCapability]

  def crypto: AccessCryptoCapability^{this} =
    new AccessCryptoCapabilityImpl(this).asInstanceOf[AccessCryptoCapability]

  def jobs: AccessJobsCapability^{this} =
    new AccessJobsCapabilityImpl(this).asInstanceOf[AccessJobsCapability]

  def conversation: AccessConversationCapability^{this} =
    new AccessConversationCapabilityImpl(this).asInstanceOf[AccessConversationCapability]

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
// Rung-2 accessor implementations (Design C). Each captures the scope (or a
// resolved client) and narrows to a rung-3 capability via `apply`. The cast
// erases the capture set so these internal types stay package-private, exactly
// as the rung-3 impls do (see the ^{this} note above).
// ---------------------------------------------------------------------------

@scala.caps.assumeSafe
private[internal] final class AccessStateCapabilityImpl(scope: DaprCapabilityImpl) extends AccessStateCapability:
  def apply(storeName: StateStoreName): StateCapability^{this} =
    new StateCapabilityImpl(scope, storeName).asInstanceOf[StateCapability]

@scala.caps.assumeSafe
private[internal] final class AccessPublishCapabilityImpl(scope: DaprCapabilityImpl) extends AccessPublishCapability:
  def apply(pubsubName: PubSubName): PublishCapability^{this} =
    new PublishCapabilityImpl(scope, pubsubName).asInstanceOf[PublishCapability]

@scala.caps.assumeSafe
private[internal] final class AccessInvokeCapabilityImpl(scope: DaprCapabilityImpl) extends AccessInvokeCapability:
  def apply(appId: AppId): InvokeCapability^{this} =
    new InvokeCapabilityImpl(scope, appId).asInstanceOf[InvokeCapability]

@scala.caps.assumeSafe
private[internal] final class AccessSecretsCapabilityImpl(scope: DaprCapabilityImpl) extends AccessSecretsCapability:
  def apply(storeName: SecretStoreName): SecretsCapability^{this} =
    new SecretsCapabilityImpl(scope, storeName).asInstanceOf[SecretsCapability]

@scala.caps.assumeSafe
private[internal] final class AccessConfigurationCapabilityImpl(scope: DaprCapabilityImpl)
    extends AccessConfigurationCapability:
  def apply(storeName: ConfigurationStoreName): ConfigurationCapability^{this} =
    new ConfigurationCapabilityImpl(scope, storeName).asInstanceOf[ConfigurationCapability]

@scala.caps.assumeSafe
private[internal] final class AccessBindingsCapabilityImpl(scope: DaprCapabilityImpl) extends AccessBindingsCapability:
  def apply(bindingName: BindingName): BindingsCapability^{this} =
    new BindingsCapabilityImpl(scope, bindingName).asInstanceOf[BindingsCapability]

@scala.caps.assumeSafe
private[internal] final class AccessLockCapabilityImpl(scope: DaprCapabilityImpl) extends AccessLockCapability:
  def apply(storeName: LockStoreName): LockCapability^{this} =
    new LockCapabilityImpl(scope, storeName).asInstanceOf[LockCapability]

@scala.caps.assumeSafe
private[internal] final class AccessCryptoCapabilityImpl(scope: DaprCapabilityImpl) extends AccessCryptoCapability:
  def apply(componentName: CryptoComponentName): CryptoCapability^{this} =
    new CryptoCapabilityImpl(scope, componentName).asInstanceOf[CryptoCapability]

@scala.caps.assumeSafe
private[internal] final class AccessConversationCapabilityImpl(scope: DaprCapabilityImpl)
    extends AccessConversationCapability:
  def apply(componentName: ConversationComponentName): ConversationCapability^{this} =
    new ConversationCapabilityImpl(scope, componentName).asInstanceOf[ConversationCapability]

@scala.caps.assumeSafe
private[internal] final class AccessJobsCapabilityImpl(scope: DaprCapabilityImpl) extends AccessJobsCapability:
  def apply(name: JobName): JobCapability^{this} =
    new JobCapabilityImpl(scope, name).asInstanceOf[JobCapability]

@scala.caps.assumeSafe
private[internal] final class AccessActorCapabilityImpl(scope: DaprCapabilityImpl) extends AccessActorCapability:
  def apply(actorType: ActorType): ActorTypeCapability^{this} =
    new ActorTypeCapabilityImpl(scope, actorType).asInstanceOf[ActorTypeCapability]

@scala.caps.assumeSafe
private[internal] final class ActorTypeCapabilityImpl(scope: DaprCapabilityImpl, val actorType: ActorType)
    extends ActorTypeCapability:
  def apply(actorId: ActorId): ActorCapability^{this} =
    ActorCapabilityImpl.build(actorType, actorId, scope.actorClient).asInstanceOf[ActorCapability]
