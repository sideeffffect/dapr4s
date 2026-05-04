package dapr.safe

import language.experimental.safe

opaque type Route = String
object Route:
  def apply(s: String): Route =
    require(s.nonEmpty, "Route must not be empty")
    s
  extension (r: Route) def value: String = r
