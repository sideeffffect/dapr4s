package dapr.safe

import language.experimental.safe

opaque type StateQuery = String
object StateQuery:
  def apply(query: String): StateQuery = query
  extension (s: StateQuery) def value: String = s
