package dapr4s.derivation

/** Marks an actor method as a reminder callback in [[ActorDefinition.derive]].
  *
  * The annotated method becomes an `ActorReminderRoute` keyed by the method name (verbatim, or the [[name `@name`]]
  * override) instead of an `ActorMethodRoute`; its result is discarded.
  */
final class reminder extends scala.annotation.StaticAnnotation

/** Marks an actor method as a timer callback in [[ActorDefinition.derive]].
  *
  * The annotated method becomes an `ActorTimerRoute` keyed by the method name (verbatim, or the [[name `@name`]]
  * override) instead of an `ActorMethodRoute`; its result is discarded.
  */
final class timer extends scala.annotation.StaticAnnotation
