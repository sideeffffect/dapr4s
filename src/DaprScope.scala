package dapr.safe

import language.experimental.captureChecking
import language.experimental.saferExceptions

/** Root capability that acts as a factory for all DAPR sub-capabilities.
  *
  * A `DaprScope` is provided as a context parameter inside
  * [[DaprRuntime.run]]. It must not outlive the `run` block — the Scala 3
  * capture checker enforces this via `^{this}` return type annotations on
  * the factory methods below.
  *
  * Note: This trait does not import `language.experimental.safe` because
  * safe mode is incompatible with saferExceptions in the same file (compiler
  * limitation in current nightly). User code that imports safe mode can still
  * use DaprScope freely.
  */
@scala.caps.assumeSafe
trait DaprScope:

  /** Obtain a [[StateCapability]] for the named state store. */
  def state(storeName: StoreName): StateCapability^{this}

  /** Obtain a [[PubSubCapability]] for the named pub/sub component. */
  def pubsub(pubsubName: PubSubName): PubSubCapability^{this}

  /** Obtain the [[ServiceInvocationCapability]] (shared; no named store). */
  def invoker: ServiceInvocationCapability^{this}

  /** Obtain a [[SecretsCapability]] for the named secrets store. */
  def secrets(storeName: SecretStoreName): SecretsCapability^{this}

  /** Obtain a [[ConfigurationCapability]] for the named configuration store. */
  def config(storeName: ConfigStoreName): ConfigurationCapability^{this}

  /** Obtain a [[BindingsCapability]] for the named output binding. */
  def binding(bindingName: BindingName): BindingsCapability^{this}

  /** Obtain a [[DistributedLockCapability]] for the named lock store. */
  def lock(storeName: StoreName): DistributedLockCapability^{this}

  /** Obtain an [[ActorCapability]] for invoking methods on a specific actor instance. */
  def actor(actorType: ActorType, actorId: ActorId): ActorCapability^{this}

  /** Obtain the [[WorkflowCapability]] for managing workflow instances. */
  def workflow: WorkflowCapability^{this}

  /** Close the underlying DAPR client. Called by [[DaprRuntime.run]].
    *
    * Library-internal: user code should not call this directly.
    * The [[DaprRuntime.run]] method calls this on scope exit (both normal and exceptional).
    */
  def close(): Unit
