package dapr4s.derivation

/** Overrides the wire name a derived capability method maps to.
  *
  * By default a derived method uses its Scala name verbatim (e.g. a method `double` maps to
  * `InvokeMethodName("double")`). Annotate the method to map it to a different wire name instead:
  *
  * {{{
  *   trait GreetingService:
  *     @name("get-stats")
  *     def stats()(using InvokeCapability, JsonCodec[StatsResponse]): StatsResponse
  * }}}
  */
final class name(val value: String) extends scala.annotation.StaticAnnotation
