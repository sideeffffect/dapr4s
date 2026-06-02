package dapr4s

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

// Test-only convenience: lets tests call Dapr.runWithEndpoints(http, grpc) { ... }
// without this being part of the published library API.
extension (obj: Dapr.type)
  def runWithEndpoints[T](httpEndpoint: URI, grpcEndpoint: URI)(body: DaprCapability ?=> T): T =
    Dapr(DaprConfig(sidecar = SidecarConfig(httpEndpoint = httpEndpoint, grpcEndpoint = grpcEndpoint))).run(body)

// Test-only DaprCapability for tests that start a DaprAppServer directly (outside Dapr.serve)
// but never reach Dapr I/O through the workflow-activity bridge — either they register no
// activities, or their activities ignore the capability. The backing client points at local
// defaults and is intentionally not closed (it lives for the test's duration). Do NOT use this
// for tests that actually exercise Dapr building blocks; those obtain a real capability via
// Dapr.runWithEndpoints against a live sidecar.
@scala.caps.assumeSafe
object TestDapr:
  def placeholderCapability: DaprCapability =
    new internal.DaprCapabilityImpl(
      new io.dapr.client.DaprClientBuilder().build(),
      new AtomicReference(null),
      new AtomicReference(null),
    )
