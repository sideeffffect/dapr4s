package dapr4s


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

  /** Obtain a [[StateCapability]] for the named state store. */
  def state(storeName: StateStoreName): StateCapability^{this}

  /** Obtain a [[PublishCapability]] for the named pub/sub component. */
  def publish(pubsubName: PubSubName): PublishCapability^{this}

  /** Obtain the [[InvokeCapability]] (shared; no named store). */
  def invoke: InvokeCapability^{this}

  /** Obtain a [[SecretsCapability]] for the named secrets store. */
  def secrets(storeName: SecretStoreName): SecretsCapability^{this}

  /** Obtain a [[ConfigurationCapability]] for the named configuration store. */
  def configuration(storeName: ConfigurationStoreName): ConfigurationCapability^{this}

  /** Obtain a [[BindingsCapability]] for the named output binding. */
  def bindings(bindingName: BindingName): BindingsCapability^{this}

  /** Obtain a [[LockCapability]] for the named lock store. */
  def lock(storeName: LockStoreName): LockCapability^{this}

  /** Obtain an [[ActorCapability]] for invoking methods on a specific actor instance. */
  def actor(actorType: ActorType, actorId: ActorId): ActorCapability^{this}

  /** Obtain the [[WorkflowCapability]] for managing workflow instances. */
  def workflow: WorkflowCapability^{this}

  /** Obtain a [[CryptoCapability]] for the named crypto component. */
  def crypto(componentName: CryptoComponentName): CryptoCapability^{this}


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

  def invoke[T](body: InvokeCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.invoke.asInstanceOf[InvokeCapability])

  def secrets(storeName: SecretStoreName)[T](body: SecretsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.secrets(storeName).asInstanceOf[SecretsCapability])

  def configuration(storeName: ConfigurationStoreName)[T](body: ConfigurationCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.configuration(storeName).asInstanceOf[ConfigurationCapability])

  def bindings(bindingName: BindingName)[T](body: BindingsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.bindings(bindingName).asInstanceOf[BindingsCapability])

  def lock(storeName: LockStoreName)[T](body: LockCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.lock(storeName).asInstanceOf[LockCapability])

  def actor(actorType: ActorType, actorId: ActorId)[T](body: ActorCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.actor(actorType, actorId).asInstanceOf[ActorCapability])

  def workflow[T](body: WorkflowCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.workflow.asInstanceOf[WorkflowCapability])

  def crypto(componentName: CryptoComponentName)[T](body: CryptoCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.crypto(componentName).asInstanceOf[CryptoCapability])
