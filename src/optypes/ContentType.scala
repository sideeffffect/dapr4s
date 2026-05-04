package dapr.safe

import language.experimental.safe

opaque type ContentType = String
object ContentType:
  def apply(s: String): ContentType =
    require(s.nonEmpty, "ContentType must not be empty")
    s
  extension (ct: ContentType) def value: String = ct
