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
    // gRPC/TLS overrides for the workflow client and runtime. Without these, the Java SDK's
    // DaprWorkflowClient / WorkflowRuntimeBuilder default to localhost:50001 and ignore the
    // gRPC endpoint configured in DaprConfig (which breaks any non-default sidecar port).
    private[internal] val workflowProperties: io.dapr.config.Properties,
) extends DaprCapability:

  import DaprCapabilityImpl.*

  // WHY ^{this}: sub-capabilities extend ExclusiveCapability, so CC infers ^{fresh} for new
  // instances. The trait declares ^{this} to prevent sub-capabilities from outliving `this`.
  // Explicit ^{this} here overrides the ^{fresh} inference and satisfies the override check.
  // The asInstanceOf cast then erases the capture set so internal Impl types stay package-private.

  def state(storeName: StoreName): StateCapability^{this} =
    new StateCapabilityImpl(this, storeName).asInstanceOf[StateCapability]

  def pubsub(pubsubName: PubSubName): PubSubCapability^{this} =
    new PubSubCapabilityImpl(this, pubsubName).asInstanceOf[PubSubCapability]

  def invoker: ServiceInvocationCapability^{this} =
    new InvokerCapabilityImpl(this).asInstanceOf[ServiceInvocationCapability]

  def secrets(storeName: SecretStoreName): SecretsCapability^{this} =
    new SecretsCapabilityImpl(this, storeName).asInstanceOf[SecretsCapability]

  def config(storeName: ConfigStoreName): ConfigurationCapability^{this} =
    new ConfigCapabilityImpl(this, storeName).asInstanceOf[ConfigurationCapability]

  def binding(bindingName: BindingName): BindingsCapability^{this} =
    new BindingsCapabilityImpl(this, bindingName).asInstanceOf[BindingsCapability]

  def lock(storeName: StoreName): DistributedLockCapability^{this} =
    new LockCapabilityImpl(this, storeName).asInstanceOf[DistributedLockCapability]

  def actor(actorType: ActorType, actorId: ActorId): ActorCapability^{this} =
    val ac = actorClientRef.get() match
      case null =>
        val newAc = new ActorClient()
        if actorClientRef.compareAndSet(null, newAc) then newAc
        else
          // Lost the CAS race — close the redundant client and use the winner's.
          try newAc.close()
          catch
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(e)             => log.log(Level.WARNING, "Failed to close redundant Dapr client after CAS loss", e)
          actorClientRef.get().nn
      case existing => existing
    ActorCapabilityImpl.build(actorType, actorId, ac).asInstanceOf[ActorCapability]

  def workflow: WorkflowCapability^{this} =
    val wc = workflowClientRef.get() match
      case null =>
        val newWc = new DaprWorkflowClient(workflowProperties)
        if workflowClientRef.compareAndSet(null, newWc) then newWc
        else
          // Lost the CAS race — close the redundant client and use the winner's.
          try newWc.close()
          catch
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(e)             => log.log(Level.WARNING, "Failed to close redundant Dapr client after CAS loss", e)
          workflowClientRef.get().nn
      case existing => existing
    new WorkflowCapabilityImpl(wc).asInstanceOf[WorkflowCapability]

  def crypto(componentName: CryptoComponentName): CryptoCapability^{this} =
    new CryptoCapabilityImpl(this, componentName).asInstanceOf[CryptoCapability]

  def jobs: JobsCapability^{this} =
    new JobsCapabilityImpl(this).asInstanceOf[JobsCapability]

  def conversation(componentName: ConversationComponentName): ConversationCapability^{this} =
    new ConversationCapabilityImpl(this, componentName).asInstanceOf[ConversationCapability]

@scala.caps.assumeSafe
private object DaprCapabilityImpl:
  private val log = Logger.getLogger("dapr4s.internal.DaprCapabilityImpl")
