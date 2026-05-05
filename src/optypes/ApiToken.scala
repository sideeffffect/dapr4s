package dapr.safe

import language.experimental.safe

opaque type ApiToken = String
object ApiToken:
  def apply(s: String): ApiToken =
    require(s.nonEmpty, "ApiToken must not be empty")
    s
  extension (t: ApiToken) def value: String = t
