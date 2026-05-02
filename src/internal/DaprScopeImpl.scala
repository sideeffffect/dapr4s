package dapr.safe.internal

import dapr.safe.*
import io.dapr.client.{DaprClient, DaprClientBuilder}
import io.dapr.actors.client.ActorClient as JavaActorClient
import io.dapr.workflows.client.DaprWorkflowClient
import language.experimental.saferExceptions
import unsafeExceptions.canThrowAny

/** Concrete implementation of [[dapr.safe.DaprScope]] backed by a real [[DaprClient]].
  *
  * All interaction with the Java SDK is confined to this file and the
  * individual `*CapabilityImpl` classes. No Java types are visible in the
  * public API.
  *
  * Marked `@scala.caps.assumeSafe` so that safe-mode user code can use
  * [[DaprScope]] (implemented by this class) through the trait interface.
  */
@scala.caps.assumeSafe
private[safe] final class DaprScopeImpl(tracked private[internal] val client: DaprClient) extends DaprScope:

  @volatile private var _closed = false

  // Lazily created — only when actor() / workflow is first called.
  @volatile private var _actorClient: JavaActorClient | Null = null
  @volatile private var _workflowClient: DaprWorkflowClient | Null = null

  def isClosed: Boolean = _closed

  def state(storeName: StoreName): StateCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new StateCapabilityImpl(this, storeName)

  def pubsub(pubsubName: PubSubName): PubSubCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new PubSubCapabilityImpl(this, pubsubName)

  def invoker: ServiceInvocationCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new InvokerCapabilityImpl(this)

  def secrets(storeName: SecretStoreName): SecretsCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new SecretsCapabilityImpl(this, storeName)

  def config(storeName: ConfigStoreName): ConfigurationCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new ConfigCapabilityImpl(this, storeName)

  def binding(bindingName: BindingName): BindingsCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new BindingsCapabilityImpl(this, bindingName)

  def lock(storeName: StoreName): DistributedLockCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    new LockCapabilityImpl(this, storeName)

  def actor(actorType: ActorType, actorId: ActorId): ActorCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    val ac = synchronized {
      if _actorClient == null then _actorClient = new JavaActorClient()
      _actorClient.nn
    }
    ActorCapabilityImpl.build(actorType, actorId, ac)

  def workflow: WorkflowCapability =
    if _closed then throw IllegalStateException("DaprScope is closed")
    val wc = synchronized {
      if _workflowClient == null then _workflowClient = new DaprWorkflowClient()
      _workflowClient.nn
    }
    new WorkflowCapabilityImpl(wc)

  def close(): Unit =
    if !_closed then
      _closed = true
      client.close()
      val ac = _actorClient
      if ac != null then ac.close()
      val wc = _workflowClient
      if wc != null then
        try wc.close()
        catch case _: InterruptedException => Thread.currentThread().interrupt()


@scala.caps.assumeSafe
private[safe] object DaprScopeImpl:

  /** Build a [[DaprScopeImpl]] using the default [[DaprClientBuilder]].
    * This is the only place where the Java [[DaprClientBuilder]] is instantiated.
    */
  def create(): DaprScopeImpl =
    val client: DaprClient = new DaprClientBuilder().build()
    new DaprScopeImpl(client)
