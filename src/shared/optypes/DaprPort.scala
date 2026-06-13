package dapr4s

import language.experimental.safe

/** Valid TCP port number for Dapr endpoint configuration.
  *
  * Must be in the range 0–65535 (enforced at construction time). Used to specify the HTTP and gRPC ports of the Dapr
  * sidecar when building [[DaprConfig]]. The default sidecar HTTP port is `3500` and the default gRPC port is `50001`.
  */
opaque type DaprPort = Int
object DaprPort:
  def apply(n: Int): DaprPort =
    require(n >= 0 && n <= 65535, s"Port must be in range 0–65535, got $n")
    n
  extension (p: DaprPort) def value: Int = p
