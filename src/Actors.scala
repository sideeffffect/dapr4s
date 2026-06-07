package dapr4s

import scala.concurrent.duration.FiniteDuration

// ---------------------------------------------------------------------------
// ActorContext — capability for actor state and reminder/timer management
// ---------------------------------------------------------------------------

/** Capability provided to every actor method invocation.
  *
  * Bundles state access and reminder/timer scheduling into a single context object scoped to the current actor
  * instance. Acquired automatically by the framework — never constructed directly.
  *
  * Use the companion-object API to call methods without naming the context:
  * {{{
  *   def increment(amount: Int)(using ActorContext): Int =
  *     val count = ActorContext.get[Int]("count").getOrElse(0)
  *     ActorContext.set("count", count + amount)
  *     count + amount
  * }}}
  */
@scala.caps.assumeSafe
trait ActorContext extends scala.caps.ExclusiveCapability:

  // --- State ------------------------------------------------------------------

  /** Fetch a state value by key.  Returns `None` if the key has not been set. */
  def get[T: JsonCodec](key: StateKey): Option[T]

  /** Store a state value under `key`. */
  def set[T: JsonCodec](key: StateKey, value: T): Unit

  /** Remove a state key. */
  def remove(key: StateKey): Unit

  // --- Reminders (persistent — survive actor deactivation/restart) ----------

  /** Schedule a persistent reminder that fires after `dueTime`.
    *
    * The reminder survives actor deactivation; the sidecar will reactivate the actor to deliver it. Recurring reminders
    * fire repeatedly every `period` after the first delivery.
    *
    * `data` is serialised with its [[JsonCodec]] and stored in the Dapr reminder payload, then deserialised and passed
    * to the matching [[ActorReminderRoute]] handler when the reminder fires.
    */
  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  ): Unit

  /** Cancel a previously registered reminder.  No-op if no reminder with `name` exists. */
  def unregisterReminder(name: ReminderName): Unit

  // --- Timers (non-persistent — lost on actor deactivation) -----------------

  /** Schedule a non-persistent timer that fires after `dueTime`.
    *
    * Unlike reminders, timers are not persisted: if the actor is deactivated before the timer fires it is silently
    * dropped. Recurring timers fire repeatedly every `period` after the first delivery.
    *
    * `data` is serialised and stored in the timer payload; it is deserialised and passed to the matching
    * [[ActorTimerRoute]] handler when the timer fires.
    */
  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  ): Unit

  /** Cancel a previously registered timer.  No-op if no timer with `name` exists. */
  def unregisterTimer(name: TimerName): Unit

/** Companion-object API for [[ActorContext]]. */
@scala.caps.assumeSafe
object ActorContext:
  def get[T: JsonCodec](key: StateKey)(using ctx: ActorContext): Option[T] = ctx.get(key)
  def set[T: JsonCodec](key: StateKey, value: T)(using ctx: ActorContext): Unit = ctx.set(key, value)
  def remove(key: StateKey)(using ctx: ActorContext): Unit = ctx.remove(key)

  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  )(using ctx: ActorContext): Unit =
    ctx.registerReminder(name, data, dueTime, period)

  def unregisterReminder(name: ReminderName)(using ctx: ActorContext): Unit =
    ctx.unregisterReminder(name)

  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: FiniteDuration,
      period: Option[FiniteDuration] = None,
  )(using ctx: ActorContext): Unit =
    ctx.registerTimer(name, data, dueTime, period)

  def unregisterTimer(name: TimerName)(using ctx: ActorContext): Unit =
    ctx.unregisterTimer(name)

// ---------------------------------------------------------------------------
// ActorMethodRoute — existential wrapper for a single actor method handler
// ---------------------------------------------------------------------------

/** Existential wrapper for a single actor method handler.
  *
  * Follows the same `AnyRef`-erasure pattern as [[InvocationRoute]].
  *
  * Use [[ActorMethodRoute.apply]] to construct instances.
  */
sealed abstract class ActorMethodRoute:
  type Req
  type Resp
  val methodName: ActorMethodName
  val reqCodec: JsonCodec[Req]
  val respCodec: JsonCodec[Resp]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[ActorMethodRoute]] values.
  *
  * WHY @assumeSafe: same capturing-lambda boundary pattern as [[InvocationRoute]].
  */
@scala.caps.assumeSafe
object ActorMethodRoute:

  def apply[Q: JsonCodec, R: JsonCodec](methodName: ActorMethodName)(
      handler: Q => R,
  ): ActorMethodRoute =
    val mn = methodName
    val rc = summon[JsonCodec[Q]]
    val wc = summon[JsonCodec[R]]
    new ActorMethodRoute:
      type Req = Q
      type Resp = R
      val methodName = mn
      val reqCodec = rc
      val respCodec = wc
      val rawHandler = handler.asInstanceOf[AnyRef]

// ---------------------------------------------------------------------------
// ActorReminderRoute — existential wrapper for a reminder callback handler
// ---------------------------------------------------------------------------

/** Existential wrapper for a reminder callback handler.
  *
  * Registered in [[ActorRoutes.reminders]]; dispatched by the framework when the Dapr sidecar delivers a reminder
  * callback for this actor instance. Follows the same `AnyRef`-erasure pattern as [[Subscription]].
  *
  * Use [[ActorReminderRoute.apply]] to construct instances.
  */
sealed abstract class ActorReminderRoute:
  type Payload
  val reminderName: ReminderName
  val codec: JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[ActorReminderRoute]] values.
  *
  * WHY @assumeSafe: same capturing-lambda boundary pattern as [[Subscription]].
  */
@scala.caps.assumeSafe
object ActorReminderRoute:

  def apply[T: JsonCodec](reminderName: ReminderName)(handler: T => Unit): ActorReminderRoute =
    val rn = reminderName
    val c = summon[JsonCodec[T]]
    new ActorReminderRoute:
      type Payload = T
      val reminderName = rn
      val codec = c
      val rawHandler = handler.asInstanceOf[AnyRef]

// ---------------------------------------------------------------------------
// ActorTimerRoute — existential wrapper for a timer callback handler
// ---------------------------------------------------------------------------

/** Existential wrapper for a timer callback handler.
  *
  * Registered in [[ActorRoutes.timers]]; dispatched by the framework when the Dapr sidecar delivers a timer callback
  * for this actor instance. Follows the same `AnyRef`-erasure pattern as [[ActorReminderRoute]].
  *
  * Use [[ActorTimerRoute.apply]] to construct instances.
  */
sealed abstract class ActorTimerRoute:
  type Payload
  val timerName: TimerName
  val codec: JsonCodec[Payload]
  // WHY AnyRef: see Subscription.rawHandler — same capture-set erasure pattern.
  private[dapr4s] val rawHandler: AnyRef

/** Factory for [[ActorTimerRoute]] values.
  *
  * WHY @assumeSafe: same capturing-lambda boundary pattern as [[Subscription]].
  */
@scala.caps.assumeSafe
object ActorTimerRoute:

  def apply[T: JsonCodec](timerName: TimerName)(handler: T => Unit): ActorTimerRoute =
    val tn = timerName
    val c = summon[JsonCodec[T]]
    new ActorTimerRoute:
      type Payload = T
      val timerName = tn
      val codec = c
      val rawHandler = handler.asInstanceOf[AnyRef]

// ---------------------------------------------------------------------------
// ActorRoutes — all routes for one actor type
// ---------------------------------------------------------------------------

/** All route handlers exposed by a single actor type.
  *
  * Returned by [[ActorDefinition.build]] on every incoming actor invocation. Contains method, reminder, and timer
  * handlers; any list may be empty if the actor does not use that feature.
  */
final case class ActorRoutes(
    methods: List[ActorMethodRoute] = Nil,
    reminders: List[ActorReminderRoute] = Nil,
    timers: List[ActorTimerRoute] = Nil,
)

@scala.caps.assumeSafe
object ActorRoutes

// ---------------------------------------------------------------------------
// ActorDefinition — server-side actor hosting descriptor
// ---------------------------------------------------------------------------

/** Describes a hosted Dapr virtual actor type.
  *
  * `build` is called by the framework on every incoming actor invocation. The fresh [[ActorContext]] scoped to that
  * instance is supplied as a `given` (it is a context-function parameter), so `ActorContext.get`/`set`/… and any
  * `(using ActorContext)` handlers resolve directly — no `given ActorContext = ctx` boilerplate. The lambda receives
  * the `ActorId` and must return the [[ActorRoutes]] for this actor type.
  *
  * {{{
  *   ActorDefinition(ActorType("Counter")) { id =>
  *     val actor = new CounterActor
  *     ActorRoutes(
  *       methods = List(
  *         ActorMethodRoute[IncrReq, Int](ActorMethodName("increment"))(actor.increment),
  *         ActorMethodRoute[Unit, Int](ActorMethodName("get"))(actor.get),
  *       ),
  *       reminders = List(
  *         ActorReminderRoute[String](ReminderName("alert"))(actor.onAlert),
  *       ),
  *     )
  *   }
  * }}}
  */
@scala.caps.assumeSafe
final class ActorDefinition(
    val actorType: ActorType,
    // WHY AnyRef: the build lambda captures DAPR capabilities. AnyRef erases the
    // capture set so ActorDefinition itself has an empty capture set and can be
    // stored in List[ActorDefinition] without carrying capability captures.
    // Access only from @assumeSafe dispatch code that casts back.
    private[dapr4s] val rawBuild: AnyRef,
):
  private[dapr4s] def build(id: ActorId, ctx: ActorContext): ActorRoutes =
    // WHY asInstanceOf chain: ActorContext now extends ExclusiveCapability,
    // so the CC checker tracks ctx with a capture set. Casting via AnyRef
    // erases the capture annotation before passing to the stored lambda.
    // The stored value is `ActorId => (ActorContext ?=> ActorRoutes)`; the inner
    // context function is a ContextFunction1 at runtime (structurally Function1),
    // so it is applied id-first then ctx.
    rawBuild.asInstanceOf[ActorId => (AnyRef => ActorRoutes)](id)(ctx.asInstanceOf[AnyRef])

/** Factory for [[ActorDefinition]] values.
  *
  * WHY @assumeSafe: the build lambda captures DAPR capabilities. We store it as `AnyRef` (`.asInstanceOf[AnyRef]`) to
  * erase its CC capture set — the same pattern used by [[Subscription]], [[InvocationRoute]], and [[ActorMethodRoute]].
  */
@scala.caps.assumeSafe
object ActorDefinition:
  def apply(actorType: ActorType)(build: ActorId => ActorContext ?=> ActorRoutes): ActorDefinition =
    new ActorDefinition(actorType, build.asInstanceOf[AnyRef])
