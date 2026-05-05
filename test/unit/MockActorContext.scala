package dapr.safe.test.unit

import dapr.safe.*

import scala.collection.mutable

/** In-memory [[ActorContext]] for unit tests.
  *
  * Pre-seed state with [[seedState]]; inspect it after method invocations with [[stateSnapshot]],
  * [[registeredReminders]], and [[registeredTimers]].
  */
@scala.caps.assumeSafe
final class MockActorContext extends ActorContext:

  private val store: mutable.Map[String, String] = mutable.Map.empty

  // reminder name → (dataJson, dueTime, period)
  private val reminders: mutable.Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    mutable.Map.empty

  // timer name → (dataJson, dueTime, period)
  private val timers: mutable.Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    mutable.Map.empty

  def seedState[T: JsonCodec](key: StateKey, value: T): Unit =
    store(key.value) = summon[JsonCodec[T]].encode(value)

  def stateSnapshot: Map[String, String] = store.toMap

  def registeredReminders: Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    reminders.toMap

  def registeredTimers: Map[String, (String, java.time.Duration, Option[java.time.Duration])] =
    timers.toMap

  def get[T: JsonCodec](key: StateKey): Option[T] =
    store.get(key.value).flatMap(json => summon[JsonCodec[T]].decode(json).toOption)

  def set[T: JsonCodec](key: StateKey, value: T): Unit =
    store(key.value) = summon[JsonCodec[T]].encode(value)

  def remove(key: StateKey): Unit =
    store.remove(key.value)
    ()

  def registerReminder[T: JsonCodec](
      name: ReminderName,
      data: T,
      dueTime: java.time.Duration,
      period: Option[java.time.Duration] = None,
  ): Unit =
    reminders(name.value) = (summon[JsonCodec[T]].encode(data), dueTime, period)

  def unregisterReminder(name: ReminderName): Unit =
    reminders.remove(name.value)
    ()

  def registerTimer[T: JsonCodec](
      name: TimerName,
      data: T,
      dueTime: java.time.Duration,
      period: Option[java.time.Duration] = None,
  ): Unit =
    timers(name.value) = (summon[JsonCodec[T]].encode(data), dueTime, period)

  def unregisterTimer(name: TimerName): Unit =
    timers.remove(name.value)
    ()
