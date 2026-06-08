package dapr4s

/** Immutable, declarative description of all inbound handlers an application exposes.
  *
  * Build a `DaprApp` using the [[Subscription]], [[InvokeRoute]], [[BindingRoute]], and [[ActorDefinition]] factory
  * objects, then return it from the [[Dapr.serve]] body:
  *
  * {{{
  *   Dapr(config).serve:
  *     val scope = summon[DaprCapability]
  *     given StateCapability  = scope.state(StateStoreName("statestore"))
  *     given PublishCapability = scope.publish(PubSubName("pubsub"))
  *     DaprApp(
  *       subscriptions = List(
  *         Subscription[OrderEvent](PubSubName("pubsub"), Topic("orders")) { event => ... }
  *       ),
  *       invokeRoutes = List(
  *         InvokeRoute[OrderRequest, OrderResponse](InvokeMethodName("place-order")) { req => ... }
  *       ),
  *       actors = List(
  *         ActorDefinition(ActorType("Counter")) { id =>
  *           val actor = new CounterActor // ActorContext is in implicit scope
  *           ActorRoutes(methods = List(ActorMethodRoute[Int, Int](ActorMethodName("increment"))(actor.increment)))
  *         }
  *       )
  *     )
  * }}}
  *
  * Two `DaprApp` values can be combined with `++` to compose service modules.
  */
final case class DaprApp(
    subscriptions: List[Subscription] = Nil,
    invokeRoutes: List[InvokeRoute] = Nil,
    bindings: List[BindingRoute] = Nil,
    workflows: List[Workflow] = Nil,
    activities: List[WorkflowActivity[?, ?]] = Nil,
    actors: List[ActorDefinition] = Nil,
    jobs: List[JobRoute] = Nil,
):
  def ++(other: DaprApp): DaprApp = DaprApp(
    subscriptions ++ other.subscriptions,
    invokeRoutes ++ other.invokeRoutes,
    bindings ++ other.bindings,
    workflows ++ other.workflows,
    activities ++ other.activities,
    actors ++ other.actors,
    jobs ++ other.jobs,
  )

  /** All structural validation problems found in this app, in deterministic order (empty == valid).
    *
    * Catches silent-shadowing collisions the dispatch layer would otherwise hide: duplicate activity/workflow names,
    * duplicate subscription routes / invocation methods / binding & job names / actor types, cross-type collisions on a
    * shared HTTP path, and collisions with framework-reserved paths. Pure — performs no I/O and starts nothing.
    *
    * Actor-internal duplicates (method/timer/reminder names) are not reported here because actor routes are built per
    * request; they are enforced at build time instead. See `docs/validation.md`.
    */
  def validationErrors: List[DaprAppValidationError] = DaprAppValidation.errors(this)

  /** Return this app unchanged if it is valid, otherwise throw a [[DaprAppValidationException]] listing every problem.
    *
    * Designed for inline use at startup: `Dapr(cfg).serve { DaprApp(...).validateOrThrow() }`. [[Dapr.serve]] already
    * calls this automatically before binding the port.
    */
  def validateOrThrow(): DaprApp =
    val errs = validationErrors
    if errs.nonEmpty then throw new DaprAppValidationException(errs)
    this

@scala.caps.assumeSafe
object DaprApp

/** Existential wrapper for a pub/sub subscription handler.
  *
  * The abstract type member `Payload` binds [[codec]] to a concrete payload type, enabling path-dependent type safety
  * when iterating `DaprApp.subscriptions`. The handler lambda is stored as `AnyRef` (CC-opaque) so the instance has an
  * empty capture set and can live in a plain `List`. Internal dispatch code (in [[dapr4s.internal.DaprAppServer]] and
  * `TestDaprApp`) casts it back using the `Payload` type member under `@assumeSafe`.
  *
  * Use [[Subscription.apply]] to construct instances.
  */
sealed abstract class Subscription:
  type Payload
  val pubsubName: PubSubName
  val topic: Topic
  val route: Route
  val codec: JsonCodec[Payload]
  // When set, the sidecar routes events that exhaust the retry policy to this topic
  // instead of dropping them. Emitted as `deadLetterTopic` in the /dapr/subscribe response.
  val deadLetterTopic: Option[Topic]
  // WHY AnyRef: stores CloudEvent[Payload] => SubscriptionResult with capture set erased.
  // CC tracks captures through typed function fields; AnyRef is opaque so the instance
  // has no CC capture set and can be stored in a plain List[Subscription].
  // Access only from @assumeSafe dispatch code that casts back with the Payload type member.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[Subscription]] values.
  *
  * WHY @assumeSafe: the handler lambda captures DAPR capabilities from the enclosing scope. Inside this `@assumeSafe`
  * companion, we store the lambda as `AnyRef` (`.asInstanceOf[AnyRef]`) to erase its CC capture set, preventing the
  * anonymous class instance from acquiring a capture set and thus allowing it to be returned as a plain `Subscription`.
  * Callers in safe mode are unaffected.
  */
@scala.caps.assumeSafe
object Subscription:

  def apply[T: JsonCodec](pubsubName: PubSubName, topic: Topic, deadLetterTopic: Option[Topic] = None)(
      handler: CloudEvent[T] => SubscriptionResult,
  ): Subscription =
    apply(pubsubName, topic, Route("/" + topic.value), deadLetterTopic)(handler)

  def apply[T: JsonCodec](
      pubsubName: PubSubName,
      topic: Topic,
      route: Route,
      deadLetterTopic: Option[Topic],
  )(
      handler: CloudEvent[T] => SubscriptionResult,
  ): Subscription =
    // WHY RENAME: val x = x in anonymous class is a Scala self-reference (x's RHS sees
    // the member x, not the outer parameter).  Capture params into fresh local vals first.
    val pn = pubsubName
    val tp = topic
    val rt = route
    val dlt = deadLetterTopic
    val c = summon[JsonCodec[T]]
    new Subscription:
      type Payload = T
      val pubsubName = pn
      val topic = tp
      val route = rt
      val codec = c
      val deadLetterTopic = dlt
      val rawHandler = handler.asInstanceOf[AnyRef]

/** Existential wrapper for a service-invocation handler.
  *
  * `Req` and `Resp` type members bind [[reqCodec]] and [[respCodec]] to concrete types. The handler is stored as
  * `AnyRef` for the same reasons as [[Subscription.rawHandler]].
  *
  * Use [[InvokeRoute.apply]] or [[InvokeRoute.withRequest]] to construct instances.
  */
sealed abstract class InvokeRoute:
  type Req
  type Resp
  val methodName: InvokeMethodName
  val reqCodec: JsonCodec[Req]
  val respCodec: JsonCodec[Resp]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef
  // true when the handler expects InvokeRequest[Req] rather than plain Req.
  private[dapr4s] val usesRequestEnvelope: Boolean

/** Factory for [[InvokeRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object InvokeRoute:

  /** Handler receives only the decoded request body. */
  def apply[Q: JsonCodec, R: JsonCodec](methodName: InvokeMethodName)(
      handler: Q => R,
  ): InvokeRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val mn = methodName
    val rc = summon[JsonCodec[Q]]
    val wc = summon[JsonCodec[R]]
    new InvokeRoute:
      type Req = Q
      type Resp = R
      val methodName = mn
      val reqCodec = rc
      val respCodec = wc
      val rawHandler = handler.asInstanceOf[AnyRef]
      val usesRequestEnvelope = false

  /** Handler receives the full [[InvokeRequest]] envelope (method name, HTTP verb, and decoded body). */
  def withRequest[Q: JsonCodec, R: JsonCodec](methodName: InvokeMethodName)(
      handler: InvokeRequest[Q] => R,
  ): InvokeRoute =
    val mn = methodName
    val rc = summon[JsonCodec[Q]]
    val wc = summon[JsonCodec[R]]
    new InvokeRoute:
      type Req = Q
      type Resp = R
      val methodName = mn
      val reqCodec = rc
      val respCodec = wc
      val rawHandler = handler.asInstanceOf[AnyRef]
      val usesRequestEnvelope = true

/** Existential wrapper for an input-binding handler.
  *
  * Use [[BindingRoute.apply]] to construct instances.
  */
sealed abstract class BindingRoute:
  type Payload
  val bindingName: BindingName
  val codec: JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[BindingRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object BindingRoute:

  def apply[T: JsonCodec](bindingName: BindingName)(
      handler: T => Unit,
  ): BindingRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val bn = bindingName
    val c = summon[JsonCodec[T]]
    new BindingRoute:
      type Payload = T
      val bindingName = bn
      val codec = c
      val rawHandler = handler.asInstanceOf[AnyRef]

/** Existential wrapper for a job trigger handler.
  *
  * A job scheduled via [[JobsCapability.schedule]] fires as an inbound trigger the sidecar POSTs to `/job/<name>`. The
  * `Payload` type member binds [[codec]] to the payload the job was scheduled with.
  *
  * Use [[JobRoute.apply]] to construct instances.
  */
sealed abstract class JobRoute:
  type Payload
  val name: JobName
  val codec: JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[JobRoute]] values.
  *
  * WHY @assumeSafe: see [[Subscription]] companion — same capturing-lambda boundary pattern.
  */
@scala.caps.assumeSafe
object JobRoute:

  def apply[T: JsonCodec](name: JobName)(
      handler: T => Unit,
  ): JobRoute =
    // WHY RENAME: avoid val x = x self-reference — see Subscription.apply comment.
    val nm = name
    val c = summon[JsonCodec[T]]
    new JobRoute:
      type Payload = T
      val name = nm
      val codec = c
      val rawHandler = handler.asInstanceOf[AnyRef]
