package dapr4s


/** Root capability that acts as a factory for all DAPR sub-capabilities.
  *
  * A `DaprCapability` is provided as a context parameter inside
  * [[Dapr.run]]. It must not outlive the `run` block — the Scala 3
  * capture checker enforces this via `^{this}` return type annotations on
  * the factory methods below.
  *
  * The factory methods can be called directly or via the companion-object
  * transformer API (see [[DaprCapability$ companion object]]):
  *
  * {{{
  *   // Direct factory style (for tests and advanced use)
  *   val cap = summon[DaprCapability]
  *   given StateCapability = cap.state(StoreName("statestore"))
  *
  *   // Transformer style (recommended for service handlers)
  *   def daprApp(using DaprCapability): DaprApp =
  *     DaprCapability.state(StoreName("statestore")) {
  *       DaprCapability.pubsub(PubSubName("pubsub")) {
  *         DaprApp(...)
  *       }
  *     }
  * }}}
  */
@scala.caps.assumeSafe
trait DaprCapability extends scala.caps.ExclusiveCapability:

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


/** Companion-object transformer API for [[DaprCapability]].
  *
  * Each method acquires a sub-capability from the ambient [[DaprCapability]]
  * context and makes it available as an implicit inside `body`, so capabilities
  * never need to be named at the call site.  Multiple capabilities are composed
  * by nesting:
  *
  * {{{
  *   def daprApp(using DaprCapability): DaprApp =
  *     DaprCapability.state(StoreName("statestore")) {
  *       DaprCapability.pubsub(PubSubName("pubsub")) {
  *         DaprApp(
  *           invocations = List(
  *             InvocationRoute[OrderRequest, OrderResponse](MethodName("place-order")) { req =>
  *               try placeOrder(req)
  *               catch case e: Exception => throw e
  *             }
  *           )
  *         )
  *       }
  *     }
  * }}}
  *
  * WHY @assumeSafe: the transformer methods call into `@assumeSafe` DaprCapability
  * trait methods.  The sub-capability returned (e.g. `cap.state(storeName)`) carries
  * a `^{cap}` capture set, but the `body` parameter uses a plain `StateCapability ?=> T`
  * (no `^`) so that handler lambdas inside the body can remain CC-pure and capture
  * the capability freely via the `@assumeSafe` AnyRef-erasure pattern in
  * `InvocationRoute`/`Subscription` companions.  `@assumeSafe` here asserts that
  * passing a `StateCapability^{cap}` to a context function expecting `StateCapability`
  * (widening the capture set) is safe, because the `^{this}` return types on the
  * trait methods prevent sub-capabilities from outliving the root scope.
  */
@scala.caps.assumeSafe
object DaprCapability:

  def state(storeName: StoreName)[T](body: StateCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.state(storeName).asInstanceOf[StateCapability])

  def pubsub(pubsubName: PubSubName)[T](body: PubSubCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.pubsub(pubsubName).asInstanceOf[PubSubCapability])

  def invoker[T](body: ServiceInvocationCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.invoker.asInstanceOf[ServiceInvocationCapability])

  def secrets(storeName: SecretStoreName)[T](body: SecretsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.secrets(storeName).asInstanceOf[SecretsCapability])

  def config(storeName: ConfigStoreName)[T](body: ConfigurationCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.config(storeName).asInstanceOf[ConfigurationCapability])

  def binding(bindingName: BindingName)[T](body: BindingsCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.binding(bindingName).asInstanceOf[BindingsCapability])

  def lock(storeName: StoreName)[T](body: DistributedLockCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.lock(storeName).asInstanceOf[DistributedLockCapability])

  def actor(actorType: ActorType, actorId: ActorId)[T](body: ActorCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.actor(actorType, actorId).asInstanceOf[ActorCapability])

  def workflow[T](body: WorkflowCapability ?=> T)(using cap: DaprCapability): T =
    body(using cap.workflow.asInstanceOf[WorkflowCapability])
