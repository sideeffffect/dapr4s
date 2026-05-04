package dapr.safe

import language.experimental.safe

opaque type ETag = String
object ETag:
  def apply(s: String): ETag = s
  extension (n: ETag) def value: String = n
