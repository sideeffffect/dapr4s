package dapr4s

import java.net.URI

// Test-only convenience: lets tests call Dapr.runWithEndpoints(http, grpc) { ... }
// without this being part of the published library API.
extension (obj: Dapr.type)
  def runWithEndpoints[T](httpEndpoint: URI, grpcEndpoint: URI)(body: DaprCapability ?=> T): T =
    Dapr(DaprConfig(sidecar = SidecarConfig(httpEndpoint = httpEndpoint, grpcEndpoint = grpcEndpoint))).run(body)
