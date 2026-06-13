package dapr4s.derivation

/** Marks an actor method as a reminder callback in [[ActorDefinitions.derive]].
  *
  * The annotated method becomes an `ActorReminderRoute` keyed by the method name (verbatim, or the [[name `@name`]]
  * override) instead of an `ActorMethodRoute`; its result is discarded.
  */
final class reminder extends scala.annotation.StaticAnnotation

/** Marks an actor method as a timer callback in [[ActorDefinitions.derive]].
  *
  * The annotated method becomes an `ActorTimerRoute` keyed by the method name (verbatim, or the [[name `@name`]]
  * override) instead of an `ActorMethodRoute`; its result is discarded.
  */
final class timer extends scala.annotation.StaticAnnotation

/** Sets the dead-letter [[dapr4s.Topic]] for a derived subscription in [[Subscriptions.derive]].
  *
  * Events that exhaust the retry policy are routed to this topic instead of being dropped.
  */
final class deadLetter(val value: String) extends scala.annotation.StaticAnnotation
