package dapr4s

import dapr4s.state.*, dapr4s.publish.*, dapr4s.invoke.*, dapr4s.secrets.*, dapr4s.configuration.*, dapr4s.bindings.*,
  dapr4s.lock.*, dapr4s.actor.*, dapr4s.workflow.*, dapr4s.crypto.*, dapr4s.jobs.*

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
