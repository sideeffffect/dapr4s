package dapr.safe

import language.experimental.safe

/** URL path segment used for custom pub/sub message delivery routing.
  *
  * Must not be empty. When specified in a subscription definition, overrides the default path (derived from the topic
  * name) that the Dapr sidecar uses to deliver messages to this application. Useful when multiple subscriptions on the
  * same topic need different handlers at different paths.
  */
opaque type Route = String
object Route:
  def apply(s: String): Route =
    require(s.nonEmpty, "Route must not be empty")
    s
  extension (r: Route) def value: String = r
