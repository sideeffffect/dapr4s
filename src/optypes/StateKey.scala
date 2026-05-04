package dapr.safe

import language.experimental.safe

opaque type StateKey = String
object StateKey:
  def apply(s: String): StateKey = s
  extension (k: StateKey) def value: String = k
