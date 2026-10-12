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
  *   given StateCapability.Instance = cap.state.instance(StateStoreName("statestore"))
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

  /** Obtain the universal [[dapr4s.state.StateCapability]] (any store); `state.instance(storeName)` narrows it to one store. */
  def state: StateCapability^{this}

  /** Obtain the universal [[dapr4s.publish.PublishCapability]] (any component); `publish.instance(pubsubName)` narrows it to one component. */
  def publish: PublishCapability^{this}

  /** Obtain the universal [[dapr4s.invoke.InvokeCapability]] (any app); `invoke.instance(appId)` narrows it to one target app. */
  def invoke: InvokeCapability^{this}

  /** Obtain the universal [[dapr4s.secrets.SecretsCapability]] (any store); `secrets.instance(storeName)` narrows it to one store. */
  def secrets: SecretsCapability^{this}

  /** Obtain the universal [[dapr4s.configuration.ConfigurationCapability]] (any store); `configuration.instance(storeName)` narrows it to one store. */
  def configuration: ConfigurationCapability^{this}

  /** Obtain the universal [[dapr4s.bindings.BindingsCapability]] (any binding); `bindings.instance(bindingName)` narrows it to one binding. */
  def bindings: BindingsCapability^{this}

  /** Obtain the universal [[dapr4s.lock.LockCapability]] (any store); `lock.instance(storeName)` narrows it to one store. */
  def lock: LockCapability^{this}

  /** Obtain the universal [[dapr4s.actor.ActorCapability]] (any actor); `actor.forType(actorType).instance(actorId)` narrows it to one instance. */
  def actor: ActorCapability^{this}

  /** Obtain the universal [[dapr4s.workflow.WorkflowCapability]] (any instance) for starting and managing workflow instances. */
  def workflow: WorkflowCapability^{this}

  /** Obtain the universal [[dapr4s.crypto.CryptoCapability]] (any component); `crypto.instance(componentName)` narrows it to one component. */
  def crypto: CryptoCapability^{this}


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
  * trait methods.  The sub-capability returned (e.g. `cap.state.instance(storeName)`) carries
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

  def state(storeName: StateStoreName)[T](body: StateCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.state.instance(storeName).asInstanceOf[StateCapability.Instance])

  def publish(pubsubName: PubSubName)[T](body: PublishCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.publish.instance(pubsubName).asInstanceOf[PublishCapability.Instance])

  /** Make the universal [[dapr4s.invoke.InvokeCapability]] available to `body` — pass it to [[dapr4s.invoke.Invoke.derive]] clients, or narrow
    * to a single target app with `instance(appId)`.
    */
  def invoke[T](body: InvokeCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.invoke.asInstanceOf[InvokeCapability])

  def secrets(storeName: SecretStoreName)[T](body: SecretsCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.secrets.instance(storeName).asInstanceOf[SecretsCapability.Instance])

  def configuration(storeName: ConfigurationStoreName)[T](body: ConfigurationCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.configuration.instance(storeName).asInstanceOf[ConfigurationCapability.Instance])

  def bindings(bindingName: BindingName)[T](body: BindingsCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.bindings.instance(bindingName).asInstanceOf[BindingsCapability.Instance])

  def lock(storeName: LockStoreName)[T](body: LockCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.lock.instance(storeName).asInstanceOf[LockCapability.Instance])

  def actor(actorType: ActorType, actorId: ActorId)[T](body: ActorCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.actor.forType(actorType).instance(actorId).asInstanceOf[ActorCapability.Instance])

  /** Make the universal [[dapr4s.workflow.WorkflowCapability]] available to `body` — the launch operations plus the per-instance
    * operations (by id, or via `instance(id)`).
    */
  def workflow[T](body: WorkflowCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.workflow.asInstanceOf[WorkflowCapability])

  def crypto(componentName: CryptoComponentName)[T](body: CryptoCapability.Instance ?=> T)(using cap: DaprCapability): T =
    body(using cap.crypto.instance(componentName).asInstanceOf[CryptoCapability.Instance])
