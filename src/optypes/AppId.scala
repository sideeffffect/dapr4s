package dapr.safe

import language.experimental.safe

opaque type AppId = String
object AppId:
  def apply(s: String): AppId =
    require(s.nonEmpty, "AppId must not be empty")
    s
  extension (n: AppId) def value: String = n
