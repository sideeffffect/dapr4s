package dapr4s.test.integration.apps

import dapr4s.*
import dapr4s.given
import language.experimental.safe
import scala.concurrent.duration.FiniteDuration

// ---------------------------------------------------------------------------
// Counter actor — stateful virtual actor with reminders and timers
// ---------------------------------------------------------------------------

/** Business logic for a simple stateful Counter actor.
  *
  * Each method receives an [[ActorContext]] as a `using` parameter; state is stored in Dapr's actor state store keyed
  * by simple strings. Reminders and timers are registered via [[ActorContext]].
  *
  * Methods:
  *   - `increment(req)` — add `req.amount` to the counter, return new count
  *   - `get(_)` — return current count (Unit input)
  *   - `reset(_)` — reset counter to 0
  *   - `scheduleReset(_)` — register a reminder to reset the counter after 1 minute
  *   - `cancelReset(_)` — unregister the pending reset reminder
  *   - `scheduleTimer(_)` — register a one-shot timer that increments by 1 after 500ms
  */
object CounterActorApp:

  val ActorTypeName = ActorType("Counter")
  private val CountKey = StateKey("count")
  private val ResetReminder = ReminderName("scheduled-reset")
  private val IncrTimer = TimerName("auto-increment")

  def increment(req: IncrRequest)(using ActorContext): CounterState =
    val current = ActorContext.get[Int](CountKey).getOrElse(0)
    val next = current + req.amount
    ActorContext.set(CountKey, next)
    CounterState(next)

  def get(ignored: Unit)(using ActorContext): CounterState =
    CounterState(ActorContext.get[Int](CountKey).getOrElse(0))

  def reset(ignored: Unit)(using ActorContext): CounterState =
    ActorContext.set(CountKey, 0)
    CounterState(0)

  def scheduleReset(ignored: Unit)(using ActorContext): Unit =
    ActorContext.registerReminder(
      ResetReminder,
      "reset",
      Dur.OneMinute,
    )

  def scheduleQuickReset(ignored: Unit)(using ActorContext): Unit =
    ActorContext.registerReminder(
      ResetReminder,
      "reset",
      Dur.OneSecond,
    )

  def cancelReset(ignored: Unit)(using ActorContext): Unit =
    ActorContext.unregisterReminder(ResetReminder)

  def onScheduledReset(msg: String)(using ActorContext): Unit =
    ActorContext.set(CountKey, 0)

  def scheduleAutoIncrement(ignored: Unit)(using ActorContext): Unit =
    ActorContext.registerTimer(
      IncrTimer,
      IncrRequest(1),
      Dur.OneSecond,
    )

  def onAutoIncrement(req: IncrRequest)(using ActorContext): Unit =
    val current = ActorContext.get[Int](CountKey).getOrElse(0)
    ActorContext.set(CountKey, current + req.amount)

  def apply(): DaprApp =
    DaprApp(
      actors = List(
        ActorDefinition(ActorTypeName) { _ =>
          ActorRoutes(
            methods = List(
              ActorMethodRoute[IncrRequest, CounterState](MethodName("increment"))(increment),
              ActorMethodRoute[Unit, CounterState](MethodName("get"))(get),
              ActorMethodRoute[Unit, CounterState](MethodName("reset"))(reset),
              ActorMethodRoute[Unit, Unit](MethodName("schedule-reset"))(scheduleReset),
              ActorMethodRoute[Unit, Unit](MethodName("cancel-reset"))(cancelReset),
              ActorMethodRoute[Unit, Unit](MethodName("schedule-quick-reset"))(scheduleQuickReset),
              ActorMethodRoute[Unit, Unit](MethodName("schedule-auto-increment"))(scheduleAutoIncrement),
            ),
            reminders = List(
              ActorReminderRoute[String](ReminderName("scheduled-reset"))(msg => onScheduledReset(msg)),
            ),
            timers = List(
              ActorTimerRoute[IncrRequest](TimerName("auto-increment"))(req => onAutoIncrement(req)),
            ),
          )
        },
      ),
    )
