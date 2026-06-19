package dapr4s

import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*, dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*


/** Root capability that acts as a factory for all DAPR sub-capabilities.
  *
  * A `DaprCapability` is provided as a context parameter inside
  * [[Dapr.run]]. It must not outlive the `run` block — the Scala 3
  * capture checker enforces this via `^{this}` return type annotations on
  * the factory methods below.
  *
  * '''Platform-specific capability surface.''' Not every Dapr building block exists
  * in every Dapr SDK: the Dapr JS SDK has no jobs or conversation API. Rather than
  * throwing `UnsupportedOperationException` at runtime, the platform-specific factory
  * methods live in the inherited platform parent trait `DaprCapabilityPlatform` (and
  * their companion transformer twins in `DaprCapabilityCompanionPlatform`) — parent
  * traits, because the companion object must sit in the same file as the trait while
  * the platform halves live in platform-tagged files. On the JVM the platform trait
  * contributes `jobs` and `conversation`; on Scala.js both platform traits are empty.
  * On a platform lacking a building block the method simply does not exist — using it
  * is a compile error, not a runtime exception.
  *
  * The factory methods can be called directly or via the companion-object
  * transformer API (see [[DaprCapability$ companion object]]):
  *
  * {{{
  *   // Direct factory style (for tests and advanced use)
  *   val cap = summon[DaprCapability]
  *   given StateCapability = cap.state(StateStoreName("statestore"))
  *
  *   // Transformer style (recommended for service handlers): a dedicated
  *   // `*App` object whose `apply` takes the capabilities it needs and
  *   // returns a [[DaprApp]] — the idiom this library promotes.
  *   object MyServiceApp:
  *     def apply()(using DaprCapability): DaprApp =
  *       DaprCapability.state(StateStoreName("statestore")) {
  *         DaprCapability.publish(PubSubName("pubsub")) {
  *           DaprApp(...)
  *         }
  *       }
  *
  *   // Built and served as `MyServiceApp()`:
  *   Dapr(config).serve(MyServiceApp())
  * }}}
  */
@scala.caps.assumeSafe
trait DaprCapability extends scala.caps.ExclusiveCapability, DaprCapabilityPlatform:

  /** Obtain the [[AccessStateCapability]] accessor; `state(storeName)` narrows it to one store. */
  def state: AccessStateCapability^{this}

  /** Obtain the [[AccessPublishCapability]] accessor; `publish(pubsubName)` narrows it to one component. */
  def publish: AccessPublishCapability^{this}

  /** Obtain the [[AccessInvokeCapability]] accessor; `invoke(appId)` narrows it to one target app. */
  def invoke: AccessInvokeCapability^{this}

  /** Obtain the [[AccessSecretsCapability]] accessor; `secrets(storeName)` narrows it to one store. */
  def secrets: AccessSecretsCapability^{this}

  /** Obtain the [[AccessConfigurationCapability]] accessor; `configuration(storeName)` narrows it to one store. */
  def configuration: AccessConfigurationCapability^{this}

  /** Obtain the [[AccessBindingsCapability]] accessor; `bindings(bindingName)` narrows it to one binding. */
  def bindings: AccessBindingsCapability^{this}

  /** Obtain the [[AccessLockCapability]] accessor; `lock(storeName)` narrows it to one store. */
  def lock: AccessLockCapability^{this}

  /** Obtain the [[AccessActorCapability]] accessor; `actor(actorType)(actorId)` narrows it to one instance. */
  def actor: AccessActorCapability^{this}

  /** Obtain the [[AccessWorkflowCapability]] accessor for managing workflow instances. */
  def workflow: AccessWorkflowCapability^{this}

  /** Obtain the [[AccessCryptoCapability]] accessor; `crypto(componentName)` narrows it to one component. */
  def crypto: AccessCryptoCapability^{this}


/** Companion-object transformer API for [[DaprCapability]].
  *
  * Each method acquires a sub-capability from the ambient [[DaprCapability]]
  * context and makes it available as an implicit inside `body`, so capabilities
  * never need to be named at the call site.  Multiple capabilities are composed
  * by nesting:
  *
  * {{{
  *   object MyServiceApp:
  *     def apply()(using DaprCapability): DaprApp =
  *       DaprCapability.state(StateStoreName("statestore")) {
  *         DaprCapability.publish(PubSubName("pubsub")) {
  *           DaprApp(
  *             invokeRoutes = List(
  *               InvokeRoute[OrderRequest, OrderResponse](InvokeMethodName("place-order")) { req =>
  *                 try placeOrder(req)
  *                 catch case e: Exception => throw e
  *               }
  *             )
  *           )
  *         }
  *       }
  * }}}
  *
  * WHY @assumeSafe: the transformer methods call into `@assumeSafe` DaprCapability
  * trait methods.  The sub-capability returned (e.g. `cap.state(storeName)`) carries
  * a `^{cap}` capture set, but the `body` parameter uses a plain `StateCapability ?=> T`
  * (no `^`) so that handler lambdas inside the body can remain CC-pure and capture
  * the capability freely via the `@assumeSafe` AnyRef-erasure pattern in
  * `InvokeRoute`/`Subscription` companions.  `@assumeSafe` here asserts that
  * passing a `StateCapability^{cap}` to a context function expecting `StateCapability`
  * (widening the capture set) is safe, because the `^{this}` return types on the
  * trait methods prevent sub-capabilities from outliving the root scope.
  */
@scala.caps.assumeSafe
object DaprCapability extends DaprCapabilityCompanionPlatform:

  def state(storeName: StateStoreName)[T](body: StateCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.state(storeName).asInstanceOf[StateCapability])

  def publish(pubsubName: PubSubName)[T](body: PublishCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.publish(pubsubName).asInstanceOf[PublishCapability])

  /** Make the [[AccessInvokeCapability]] accessor available to `body` — pass it to [[Invoke.derive]] clients, or narrow
    * to a single target app with `apply(appId)`.
    */
  def invoke[T](body: AccessInvokeCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.invoke.asInstanceOf[AccessInvokeCapability])

  def secrets(storeName: SecretStoreName)[T](body: SecretsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.secrets(storeName).asInstanceOf[SecretsCapability])

  def configuration(storeName: ConfigurationStoreName)[T](body: ConfigurationCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.configuration(storeName).asInstanceOf[ConfigurationCapability])

  def bindings(bindingName: BindingName)[T](body: BindingsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.bindings(bindingName).asInstanceOf[BindingsCapability])

  def lock(storeName: LockStoreName)[T](body: LockCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.lock(storeName).asInstanceOf[LockCapability])

  def actor(actorType: ActorType, actorId: ActorId)[T](body: ActorCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.actor(actorType)(actorId).asInstanceOf[ActorCapability])

  /** Make the [[AccessWorkflowCapability]] accessor available to `body` — the launch operations plus `apply(id)` for
    * per-instance operations.
    */
  def workflow[T](body: AccessWorkflowCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.workflow.asInstanceOf[AccessWorkflowCapability])

  def crypto(componentName: CryptoComponentName)[T](body: CryptoCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.crypto(componentName).asInstanceOf[CryptoCapability])
