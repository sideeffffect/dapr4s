package dapr4s

/** A single structural problem found in a [[DaprApp]] (or in the [[ActorRoutes]] an actor builds).
  *
  * Every case represents a *silent* misconfiguration: a collision the dispatch layer
  * ([[dapr4s.internal.DaprAppServer]]) would resolve last-write-wins or first-match-wins, sending traffic to the wrong
  * handler with no error. See `docs/validation.md` for the rationale behind each check.
  */
enum DaprAppValidationError:
  /** Two or more activities register under the same wire name ([[WorkflowActivity.activityName]]). */
  case DuplicateActivityName(name: String, count: Int)

  /** Two or more workflows register under the same simple class name (the runtime registration key — workflows in
    * different packages with the same simple name collide).
    */
  case DuplicateWorkflowName(simpleName: String, count: Int)

  /** Two or more subscriptions map to the same HTTP route path. */
  case DuplicateSubscriptionRoute(path: String, count: Int)

  /** Two or more invokeRoutes register under the same method name. */
  case DuplicateInvocationMethod(name: String, count: Int)

  /** Two or more input bindings register under the same name. */
  case DuplicateBindingName(name: String, count: Int)

  /** Two or more jobs register under the same name. */
  case DuplicateJobName(name: String, count: Int)

  /** Two or more actor definitions register the same actor type. */
  case DuplicateActorType(actorType: String, count: Int)

  /** Handlers of two or more differing kinds (pub/sub, binding, invocation) map to the same effective HTTP path; the
    * fixed dispatch order silently picks one.
    */
  case RouteCollision(path: String, kinds: List[String])

  /** A user handler's effective HTTP path collides with a framework-reserved path and is silently shadowed. */
  case ReservedPathCollision(path: String, kind: String, reserved: String)

  /** Two or more methods within one actor share a name. */
  case DuplicateActorMethod(actorType: String, name: String, count: Int)

  /** Two or more reminders within one actor share a name. */
  case DuplicateActorReminder(actorType: String, name: String, count: Int)

  /** Two or more timers within one actor share a name. */
  case DuplicateActorTimer(actorType: String, name: String, count: Int)

  /** Human-readable, single-line description used when aggregating errors into an exception message. */
  def message: String = this match
    case DuplicateActivityName(name, count) =>
      s"$count activities are registered under the same name '$name'; only one would be reachable."
    case DuplicateWorkflowName(simpleName, count) =>
      s"$count workflows register under the same simple class name '$simpleName' (the runtime registration key); " +
        "only one would be reachable. Rename one of the workflow classes."
    case DuplicateSubscriptionRoute(path, count) =>
      s"$count subscriptions map to the same route '$path'; only one would receive events."
    case DuplicateInvocationMethod(name, count) =>
      s"$count invokeRoutes register under the same method name '$name'; only one would be reachable."
    case DuplicateBindingName(name, count) =>
      s"$count input bindings register under the same name '$name'; only one would be reachable."
    case DuplicateJobName(name, count) =>
      s"$count jobs register under the same name '$name'; only one would be reachable."
    case DuplicateActorType(actorType, count) =>
      s"$count actor definitions register the same actor type '$actorType'; only one would be reachable."
    case RouteCollision(path, kinds) =>
      s"path '$path' is claimed by handlers of differing kinds (${kinds.mkString(", ")}); " +
        "dispatch silently picks one. Give them distinct names/routes."
    case ReservedPathCollision(path, kind, reserved) =>
      s"$kind path '$path' collides with the framework-reserved path '$reserved' and would be silently shadowed."
    case DuplicateActorMethod(actorType, name, count) =>
      s"actor '$actorType' exposes $count methods named '$name'; only the first would be dispatched."
    case DuplicateActorReminder(actorType, name, count) =>
      s"actor '$actorType' exposes $count reminders named '$name'; only the first would be dispatched."
    case DuplicateActorTimer(actorType, name, count) =>
      s"actor '$actorType' exposes $count timers named '$name'; only the first would be dispatched."

/** Thrown by [[DaprApp.validateOrThrow]], by [[Dapr.serve]]'s startup check, and by an actor build that produces
  * duplicate route names. The message lists every error so a user can fix them all in one pass.
  */
final class DaprAppValidationException(val errors: List[DaprAppValidationError])
    extends IllegalStateException(
      s"DaprApp validation failed with ${errors.size} error(s):\n" + errors.map("  - " + _.message).mkString("\n"),
    )

/** Pure structural validation of a [[DaprApp]] and of the [[ActorRoutes]] an actor builds. */
@scala.caps.assumeSafe
private[dapr4s] object DaprAppValidation:

  // Framework paths that win over the user `/` catch-all in DaprAppServer (exact context or owning prefix).
  // A user path collides when it equals one of these or sits underneath it.
  private val reservedPaths: List[String] =
    List("/dapr/subscribe", "/dapr/config", "/actors", "/job")

  /** Group identical strings and report each key occurring more than once, ascending by key. */
  private def duplicates(items: List[String]): List[(String, Int)] =
    items.groupBy(identity).view.mapValues(_.size).filter(_._2 > 1).toList.sortBy(_._1)

  /** Effective HTTP path of a subscription route — mirrors the normalisation in DaprAppServer. */
  private def subscriptionPath(s: Subscription): String =
    if s.route.value.startsWith("/") then s.route.value else "/" + s.route.value

  private def bindingPath(b: BindingRoute): String = "/" + b.bindingName.value
  private def invocationPath(i: InvokeRoute): String = "/" + i.methodName.value

  private def reservedHitFor(path: String): Option[String] =
    reservedPaths.find(r => path == r || path.startsWith(r + "/"))

  /** All validation problems found in `app`, in deterministic order. Empty == valid. */
  def errors(app: DaprApp): List[DaprAppValidationError] =
    import DaprAppValidationError.*

    val activityErrors =
      duplicates(app.activities.map(_.activityName)).map((n, c) => DuplicateActivityName(n, c))

    val workflowErrors =
      duplicates(app.workflows.map(_.getClass.getSimpleName.nn)).map((n, c) => DuplicateWorkflowName(n, c))

    val subscriptionErrors =
      duplicates(app.subscriptions.map(subscriptionPath)).map((p, c) => DuplicateSubscriptionRoute(p, c))

    val invocationErrors =
      duplicates(app.invokeRoutes.map(_.methodName.value)).map((n, c) => DuplicateInvocationMethod(n, c))

    val bindingErrors =
      duplicates(app.bindings.map(_.bindingName.value)).map((n, c) => DuplicateBindingName(n, c))

    val jobErrors =
      duplicates(app.jobs.map(_.name.value)).map((n, c) => DuplicateJobName(n, c))

    val actorErrors =
      duplicates(app.actors.map(_.actorType.value)).map((t, c) => DuplicateActorType(t, c))

    // Cross-type collisions in the shared root namespace: (path, kind) for every pub/sub, binding, invocation handler.
    val rootHandlers: List[(String, String)] =
      app.subscriptions.map(s => subscriptionPath(s) -> "pub/sub") :::
        app.bindings.map(b => bindingPath(b) -> "binding") :::
        app.invokeRoutes.map(i => invocationPath(i) -> "invocation")

    val routeCollisionErrors =
      rootHandlers
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2).distinct)
        .filter(_._2.sizeIs > 1)
        .toList
        .sortBy(_._1)
        .map((p, kinds) => RouteCollision(p, kinds.sorted))

    val reservedErrors =
      rootHandlers.distinct.sortBy(_._1).flatMap { (path, kind) =>
        reservedHitFor(path).map(r => ReservedPathCollision(path, kind, r))
      }

    activityErrors ::: workflowErrors ::: subscriptionErrors ::: invocationErrors :::
      bindingErrors ::: jobErrors ::: actorErrors ::: routeCollisionErrors ::: reservedErrors

  /** Validate the [[ActorRoutes]] produced by an actor build; throw on any duplicate method/reminder/timer name.
    *
    * Runs on every `ActorDefinition.build`, so it is correct even when the build lambda returns different routes per
    * `ActorId`; the cost is a cheap list scan per invocation.
    */
  def checkActorRoutes(actorType: ActorType, routes: ActorRoutes): Unit =
    import DaprAppValidationError.*
    val errs =
      duplicates(routes.methods.map(_.methodName.value)).map((n, c) => DuplicateActorMethod(actorType.value, n, c)) :::
        duplicates(routes.reminders.map(_.reminderName.value)).map((n, c) =>
          DuplicateActorReminder(actorType.value, n, c),
        ) :::
        duplicates(routes.timers.map(_.timerName.value)).map((n, c) => DuplicateActorTimer(actorType.value, n, c))
    if errs.nonEmpty then throw new DaprAppValidationException(errs)
