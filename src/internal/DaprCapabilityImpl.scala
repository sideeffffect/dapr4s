package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.DaprClient
import io.dapr.actors.client.ActorClient
import io.dapr.workflows.client.DaprWorkflowClient
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Concrete implementation of [[dapr.safe.DaprCapability]] backed by a real [[DaprClient]].
  *
  * All interaction with the Java SDK is confined to this file and the individual `*CapabilityImpl` classes. No Java
  * types are visible in the public API.
  *
  * Lifecycle: [[dapr.safe.DaprRuntime.run]] owns all three clients; it creates them, passes them here, and closes them
  * in its `finally` block. `actorClientRef` and `workflowClientRef` start as `null` and are lazily populated on first
  * use via `AtomicReference.compareAndSet`, so [[dapr.safe.DaprRuntime.run]] can read the refs at teardown and close
  * only what was actually created.
  *
  * Marked `@scala.caps.assumeSafe` so that safe-mode user code can use [[DaprCapability]] (implemented by this class)
  * through the trait interface.
  */
@scala.caps.assumeSafe
private[safe] final class DaprCapabilityImpl(
    private[internal] val client: DaprClient,
    private val actorClientRef: AtomicReference[ActorClient],
    private val workflowClientRef: AtomicReference[DaprWorkflowClient],
) extends DaprCapability:

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
            case NonFatal(_)             => ()
          actorClientRef.get().nn
      case existing => existing
    ActorCapabilityImpl.build(actorType, actorId, ac).asInstanceOf[ActorCapability]

  def workflow: WorkflowCapability^{this} =
    val wc = workflowClientRef.get() match
      case null =>
        val newWc = new DaprWorkflowClient()
        if workflowClientRef.compareAndSet(null, newWc) then newWc
        else
          // Lost the CAS race — close the redundant client and use the winner's.
          try newWc.close()
          catch
            case _: InterruptedException => Thread.currentThread().interrupt()
            case NonFatal(_)             => ()
          workflowClientRef.get().nn
      case existing => existing
    new WorkflowCapabilityImpl(wc).asInstanceOf[WorkflowCapability]
