package dapr.safe

import language.experimental.safe

opaque type DaprPort = Int
object DaprPort:
  def apply(n: Int): DaprPort =
    require(n >= 0 && n <= 65535, s"Port must be in range 0–65535, got $n")
    n
  extension (p: DaprPort) def value: Int = p
